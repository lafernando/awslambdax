import ballerina/http;
import ballerinax/hello;

@hello:Greeting{salutation : "Guten Tag!"}
@http:ServiceConfig {
    basePath:"/helloWorld"
}
service<http:Service> helloWorld bind {port:9091} {
    sayHello(endpoint outboundEP, http:Request request) {
        http:Response response = new;
        response.setStringPayload("Hello, World from service helloWorld ! \n");
        _ = outboundEP -> respond(response);
    }
}
