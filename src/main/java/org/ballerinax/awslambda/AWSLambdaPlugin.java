/*
 * Copyright (c) 2018, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.ballerinax.awslambda;

import org.ballerinalang.compiler.plugins.AbstractCompilerPlugin;
import org.ballerinalang.compiler.plugins.SupportedAnnotationPackages;
import org.ballerinalang.model.TreeBuilder;
import org.ballerinalang.model.elements.Flag;
import org.ballerinalang.model.elements.PackageID;
import org.ballerinalang.model.tree.AnnotationAttachmentNode;
import org.ballerinalang.model.tree.FunctionNode;
import org.ballerinalang.model.tree.IdentifierNode;
import org.ballerinalang.model.tree.PackageNode;
import org.ballerinalang.model.types.TypeTags;
import org.ballerinalang.util.diagnostic.Diagnostic;
import org.ballerinalang.util.diagnostic.DiagnosticLog;
import org.ballerinalang.util.exceptions.BallerinaException;
import org.wso2.ballerinalang.compiler.desugar.ASTBuilderUtil;
import org.wso2.ballerinalang.compiler.semantics.model.Scope;
import org.wso2.ballerinalang.compiler.semantics.model.SymbolTable;
import org.wso2.ballerinalang.compiler.semantics.model.symbols.BAnnotationSymbol;
import org.wso2.ballerinalang.compiler.semantics.model.symbols.BInvokableSymbol;
import org.wso2.ballerinalang.compiler.semantics.model.symbols.BPackageSymbol;
import org.wso2.ballerinalang.compiler.semantics.model.symbols.BSymbol;
import org.wso2.ballerinalang.compiler.semantics.model.symbols.Symbols;
import org.wso2.ballerinalang.compiler.semantics.model.types.BInvokableType;
import org.wso2.ballerinalang.compiler.semantics.model.types.BNilType;
import org.wso2.ballerinalang.compiler.tree.BLangAnnotationAttachment;
import org.wso2.ballerinalang.compiler.tree.BLangFunction;
import org.wso2.ballerinalang.compiler.tree.BLangIdentifier;
import org.wso2.ballerinalang.compiler.tree.BLangImportPackage;
import org.wso2.ballerinalang.compiler.tree.BLangPackage;
import org.wso2.ballerinalang.compiler.tree.expressions.BLangExpression;
import org.wso2.ballerinalang.compiler.tree.expressions.BLangInvocation;
import org.wso2.ballerinalang.compiler.tree.expressions.BLangLiteral;
import org.wso2.ballerinalang.compiler.tree.expressions.BLangSimpleVarRef;
import org.wso2.ballerinalang.compiler.tree.statements.BLangBlockStmt;
import org.wso2.ballerinalang.compiler.tree.statements.BLangExpressionStmt;
import org.wso2.ballerinalang.compiler.tree.types.BLangType;
import org.wso2.ballerinalang.compiler.tree.types.BLangUnionTypeNode;
import org.wso2.ballerinalang.compiler.util.CompilerContext;
import org.wso2.ballerinalang.compiler.util.Name;
import org.wso2.ballerinalang.compiler.util.diagnotic.DiagnosticPos;
import org.wso2.ballerinalang.util.Flags;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Compiler plugin to process AWS lambda function annotations.
 */
@SupportedAnnotationPackages(value = "ballerinax/awslambda:0.0.0")
public class AWSLambdaPlugin extends AbstractCompilerPlugin {

    private static final String LAMBDA_ENTRYPOINT_FUNCTION = "__d47ff0e4_cb4f_40a7_acde_5daf8f50043c";
    
    private DiagnosticLog dlog;
    
    private SymbolTable symTable = null;
    
    @Override
    public void init(DiagnosticLog diagnosticLog) {
        this.dlog = diagnosticLog;
    }
    
    public void setCompilerContext(CompilerContext context) {
        this.symTable = SymbolTable.getInstance(context);
    }
        
    @Override
    public void process(PackageNode packageNode) {
        List<BLangFunction> lambdaFunctions = new ArrayList<>();
        for (FunctionNode fn : packageNode.getFunctions()) {
            BLangFunction bfn = (BLangFunction) fn;
            if (this.isLambdaFunction(bfn)) {
                System.out.println("Lambda: " + fn);
                lambdaFunctions.add(bfn);
            }
        }
        BLangPackage myPkg = (BLangPackage) packageNode;
        if (!lambdaFunctions.isEmpty()) {
            BPackageSymbol lambdaPkgSymbol = this.extractLambdaPackageSymbol(myPkg);
            if (lambdaPkgSymbol == null) {
                // this symbol will always be there, since the import is needed to add the annotation
                throw new BallerinaException("AWS Lambda package symbol cannot be found");
            }
            System.out.println("*** AAA: " + lambdaPkgSymbol);
            System.out.println("Generating code for lamdba...");
            BLangFunction epFunc = this.createFunction(myPkg.pos, LAMBDA_ENTRYPOINT_FUNCTION, (BLangPackage) packageNode);
            packageNode.addFunction(epFunc);
            for (BLangFunction lambdaFunc : lambdaFunctions) {
                this.addRegisterCall(lambdaPkgSymbol, lambdaFunc.body, lambdaFunc);
            }
            this.addProcessCall(lambdaPkgSymbol, epFunc.body);
        }
    }
    
    private BPackageSymbol extractLambdaPackageSymbol(BLangPackage myPkg) {
        for (BLangImportPackage pi : myPkg.imports) {
            if ("ballerinax".equals(pi.orgName.value) && pi.pkgNameComps.size() == 1 && 
                    "awslambda".equals(pi.pkgNameComps.get(0).value)) {
                return pi.symbol;
            }
        }
        return null;
    }
    
    private void addRegisterCall(BPackageSymbol lamdaPkgSymbol, BLangBlockStmt blockStmt, BLangFunction func) {
        List<BLangExpression> exprs = new ArrayList<>();
        exprs.add(this.createStringLiteral(blockStmt.pos, func.name.value));
        exprs.add(this.createVariableRef(blockStmt.pos, func.symbol));
        BLangInvocation inv = this.createInvocationNode(lamdaPkgSymbol, "__register", exprs);
        BLangExpressionStmt stmt = new BLangExpressionStmt(inv);
        stmt.pos = blockStmt.pos;
        blockStmt.addStatement(stmt);
    }
    
    private BLangLiteral createStringLiteral(DiagnosticPos pos, String value) {
        BLangLiteral stringLit = new BLangLiteral();
        stringLit.pos = pos;
        stringLit.value = value;
        stringLit.type = symTable.stringType;
        return stringLit;
    }
    
    private BLangSimpleVarRef createVariableRef(DiagnosticPos pos, BSymbol varSymbol) {
        final BLangSimpleVarRef varRef = (BLangSimpleVarRef) TreeBuilder.createSimpleVariableReferenceNode();
        varRef.pos = pos;
        varRef.variableName = ASTBuilderUtil.createIdentifier(pos, varSymbol.name.value);
        varRef.symbol = varSymbol;
        varRef.type = varSymbol.type;
        return varRef;
    }
    
    private void addProcessCall(BPackageSymbol lamdaPkgSymbol, BLangBlockStmt blockStmt) {
        System.out.println("X: " + lamdaPkgSymbol + ":" + blockStmt);
        BLangInvocation inv = this.createInvocationNode(lamdaPkgSymbol, "__process", new ArrayList<>(0));
        BLangExpressionStmt stmt = new BLangExpressionStmt(inv);
        stmt.pos = blockStmt.pos;
        blockStmt.addStatement(stmt);
    }
    
    private BLangInvocation createInvocationNode(BPackageSymbol pkgSymbol, String functionName, List<BLangExpression> args) {
        BLangInvocation invocationNode = (BLangInvocation) TreeBuilder.createInvocationNode();
        BLangIdentifier name = (BLangIdentifier) TreeBuilder.createIdentifierNode();
        name.setLiteral(false);
        name.setValue(functionName);
        invocationNode.name = name;
        invocationNode.pkgAlias = (BLangIdentifier) TreeBuilder.createIdentifierNode();
        invocationNode.symbol = pkgSymbol.scope.lookup(new Name(functionName)).symbol;
        if (invocationNode.symbol == null) {
            return null;
        }
        invocationNode.type = new BNilType();
        invocationNode.requiredArgs = args;
        return invocationNode;
    }
    
    private BLangFunction createFunction(DiagnosticPos pos, String name, BLangPackage packageNode) {
        final BLangFunction bLangFunction = (BLangFunction) TreeBuilder.createFunctionNode();
        final IdentifierNode funcName = ASTBuilderUtil.createIdentifier(pos, name);
        bLangFunction.setName(funcName);
        bLangFunction.flagSet = EnumSet.of(Flag.PUBLIC);
        bLangFunction.pos = pos;
        bLangFunction.type = new BInvokableType(new ArrayList<>(), new BNilType(), null);
        bLangFunction.body = this.createBlockStmt(pos);
        BInvokableSymbol functionSymbol = Symbols.createFunctionSymbol(Flags.asMask(bLangFunction.flagSet),
                new Name(bLangFunction.name.value), packageNode.packageID, bLangFunction.type, packageNode.symbol, true);
        functionSymbol.scope = new Scope(functionSymbol);
        bLangFunction.symbol = functionSymbol;
        return bLangFunction;
    }
    
    private BLangBlockStmt createBlockStmt(DiagnosticPos pos) {
        final BLangBlockStmt blockNode = (BLangBlockStmt) TreeBuilder.createBlockNode();
        blockNode.pos = pos;
        return blockNode;
    }
    
    private boolean isLambdaFunction(BLangFunction fn) {
        List<BLangAnnotationAttachment> annotations = fn.annAttachments;
        boolean hasLambdaAnnon = false;
        for (AnnotationAttachmentNode attachmentNode : annotations) {
            hasLambdaAnnon = this.hasLambaAnnotation(attachmentNode);
            if (hasLambdaAnnon) {
                break;
            }
        }
        if (hasLambdaAnnon) {
            BLangFunction bfn = (BLangFunction) fn;
            if (!this.validateLambdaFunction(bfn)) {
                dlog.logDiagnostic(Diagnostic.Kind.ERROR, fn.getPosition(), "Invalid function signature for an AWS lambda function: " + 
                        bfn + ", it should be 'public function (awslambda:Context, json) returns json|error'");
                return false;
            } else {
                return true;
            }
        } else {        
            return false;
        }
    }
    
    private boolean validateLambdaFunction(BLangFunction node) {
        if (node.requiredParams.size() != 2 || node.defaultableParams.size() > 0 || node.restParam != null) {
            return false;
        }
        BLangType type1 = (BLangType) node.requiredParams.get(0).getTypeNode();
        BLangType type2 = (BLangType) node.requiredParams.get(1).getTypeNode();
        if (!type1.type.tsymbol.name.value.equals("Context")) {
            return false;
        }
        if (!type1.type.tsymbol.pkgID.orgName.value.equals("ballerinax") || 
                !type1.type.tsymbol.pkgID.name.value.equals("awslambda")) {
            return false;
        }
        if (type2.type.tag != TypeTags.JSON_TAG) {
            return false;
        }
        BLangType retType = (BLangType) node.returnTypeNode;
        if (!(retType instanceof BLangUnionTypeNode)) {
            return false;
        }
        BLangUnionTypeNode unionType = (BLangUnionTypeNode) retType;
        if (unionType.memberTypeNodes.size() != 2) {
            return false;
        }
        Set<Integer> typeTags = new HashSet<>();
        typeTags.add(unionType.memberTypeNodes.get(0).type.tag);
        typeTags.add(unionType.memberTypeNodes.get(1).type.tag);
        typeTags.remove(TypeTags.JSON_TAG);
        typeTags.remove(TypeTags.ERROR_TAG);
        if (!typeTags.isEmpty()) {
            return false;
        }        
        return true;
    }
    
    private boolean hasLambaAnnotation(AnnotationAttachmentNode attachmentNode) {
        BAnnotationSymbol symbol = ((BLangAnnotationAttachment) attachmentNode).annotationSymbol;
        return "ballerinax".equals(symbol.pkgID.orgName.value) && 
                "awslambda".equals(symbol.pkgID.name.value) && "Function".equals(symbol.name.value);
    }

    @Override
    public void codeGenerated(PackageID packageID, Path binaryPath) {
        //extract file name.
        String filePath = binaryPath.toAbsolutePath().toString().replace(".balx", ".txt");
        String greeting = AWSLambdaModel.getInstance().getGreeting();
        try {
            writeToFile(greeting, filePath);
        } catch (IOException e) {
            dlog.logDiagnostic(Diagnostic.Kind.ERROR, null, e.getMessage());
        }
    }

    /**
     * Write content to a File. Create the required directories if they don't not exists.
     *
     * @param context        context of the file
     * @param targetFilePath target file path
     * @throws IOException If an error occurs when writing to a file
     */
    public void writeToFile(String context, String targetFilePath) throws IOException {
        File newFile = new File(targetFilePath);
        // append if file exists
        if (newFile.exists()) {
            Files.write(Paths.get(targetFilePath), context.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.APPEND);
            return;
        }
        //create required directories
        if (newFile.getParentFile().mkdirs()) {
            Files.write(Paths.get(targetFilePath), context.getBytes(StandardCharsets.UTF_8));
            return;
        }
        Files.write(Paths.get(targetFilePath), context.getBytes(StandardCharsets.UTF_8));
    }
}
