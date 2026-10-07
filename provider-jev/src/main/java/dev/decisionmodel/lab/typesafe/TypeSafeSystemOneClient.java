package dev.decisionmodel.lab.typesafe;

import org.eclipse.microprofile.rest.client.annotation.RegisterProvider;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;

@Path("/v1/systemone")
@RegisterRestClient(configKey = "typesafe")
@RegisterProvider(TypeSafeApiExceptionMapper.class)
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public interface TypeSafeSystemOneClient {

    @POST
    SystemOneDtos.Response evaluate(
            @HeaderParam(HttpHeaders.AUTHORIZATION) String authorization,
            SystemOneDtos.Request request);
}
