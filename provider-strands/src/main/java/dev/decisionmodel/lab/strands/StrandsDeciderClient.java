package dev.decisionmodel.lab.strands;

import org.eclipse.microprofile.rest.client.annotation.RegisterProvider;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

@Path("/v1/systemone")
@RegisterRestClient(configKey = "strands-decider")
@RegisterProvider(StrandsDeciderApiExceptionMapper.class)
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public interface StrandsDeciderClient {

    @POST
    StrandsSystemOneDtos.Response evaluate(StrandsSystemOneDtos.Request request);
}
