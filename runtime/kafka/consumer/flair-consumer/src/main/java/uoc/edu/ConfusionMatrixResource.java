package uoc.edu;

import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

@Path("/flairs")
@ApplicationScoped
public class ConfusionMatrixResource extends BaseResource {

    @GET
    @Path("/confusion-matrix")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getConfusionMatrix() {
        JsonObject response = new JsonObject();
        Set<String> allFlairs = new TreeSet<>();
        confusionMatrix.forEach((sk, innerMap) -> {
            allFlairs.add(sk);
            allFlairs.addAll(innerMap.keySet());
        });

        List<String> flairList = new ArrayList<>(allFlairs);
        Collections.sort(flairList); // ensure matrix is ordered consistently

        int size = flairList.size();
        int[][] matrix = new int[size][size];

        for (int i = 0; i < size; i++) {
            String sk = flairList.get(i);
            Map<String, Integer> row = confusionMatrix.getOrDefault(sk, new HashMap<>());
            for (int j = 0; j < size; j++) {
                String tf = flairList.get(j);
                matrix[i][j] = row.getOrDefault(tf, 0);
            }
        }

        response.put("labels", flairList);
        response.put("matrix", matrix);

        return Response.ok(response).build();
    }
}
