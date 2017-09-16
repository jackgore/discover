import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.typesafe.config.ConfigFactory;
import org.apache.http.HttpStatus;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.lang.reflect.Type;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;

/**
 * Created by jack on 2017-03-18.
 * Singleton server class
 */
public class Server {

    private static final String HTTP_POST = "POST";
    private static final String HTTP_GET = "GET";
    private static final String HTTP_DELETE = "DELETE";

    private final Type T_LIST_OF_EVENTS = new TypeToken<List<Event>>(){}.getType();
    private final Type T_EVENT = new TypeToken<Event>(){}.getType();

    private static Logger logger = LogManager.getLogger(Server.class);
    private static Server server = null;
    private EventManager eventManager = null;
    private HttpServer httpServer = null;
    private Gson gson = null;

    public static Server getInstance() {
        if(server == null) {
            server = new Server();
        }
        return server;
    }

    /**
     * Creates a server on the port designated in configuration file
     * @throws IOException
     */
    private void createServer() throws IOException {
        // Pull out port to be configured
        logger.info("Creating server...");
        httpServer = HttpServer.create(new InetSocketAddress(8000), 0);
        createEndpoints();
        httpServer.setExecutor(null); // creates a default executor
    }

    // Starts the server
    public void start() {
        httpServer.start();
    }

    private void createEndpoints() {
        logger.info("Registering endpoints..");
        httpServer.createContext("/events", new EventHandler());
        httpServer.createContext("/test", new MyHandler());
    }

    // Constructor
    private Server() {

        // Pass in the config. This can eventually be changed to have different files
        eventManager = new EventManager(new ElasticDataClient(ConfigFactory.load()));

        gson = new Gson();
        // Try to create the server otherwise throw
        try {
            createServer();
        } catch (IOException ex) {
            throw new RuntimeException("Unable to create server shutting down...");
        }
    }

    /**
     * Returns the request type of a given HttpExchange t
     * @param t the HttpExchange
     * @return one of POST, GET, PATCH... etc
     */
    private String getRequestType(HttpExchange t) {
        return t.getRequestMethod();
    }

    static class MyHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            ResponseWriter.write(t, new Response(200, "This is the response"));
        }
    }

    /**
     * Handler for the /events endpoint
     */
    private class EventHandler implements HttpHandler {

        /**
         * Handles a GET request for the events endpoint
         * @return Response object that is to be used as the response
         */
        private Response handleGET(HttpExchange t) {
            Response response;

            // The query provided by the request
            String query = t.getRequestURI().getQuery();
            logger.info("Received GET request at /events with query {}.", query );

            // Create a map from the received query
            Map<String, String> values = Utilities.queryToMap(query);

            // Check if it contains id parameter we want to provide a single
            // event as the response.
            if(values.containsKey("id")) {
                // For now we only want to support GET for a single id
                String id = values.get("id");
                // Retrieve the event for the given id
                Event event = eventManager.getEvent(id);
                // TODO what if this event doesn't exist

                String responseMessage = gson.toJson(event, T_EVENT);
                response = new Response(HttpStatus.SC_OK, responseMessage);
            } else {
                // Get the events form the event manager and convert to usable JSON array.
                List<String> events = eventManager.getEvents();
                String arr = Utilities.objectsToJSONArray(events);
                // TODO: Change this to rely on provide events within range of given coordinates (lat, long)
                response = new Response(HttpStatus.SC_OK, arr);

            }
            logger.info("Sending response: Status: {} Message: {}", response.getStatusCode(), response.getMessage());
            return response;
        }

        /**
         * Handles a DELETE request for the events endpoint
         * @return the String that is to be used as the response
         */
        private Response handleDELETE(HttpExchange t) {
            Response response;
            String query = t.getRequestURI().getQuery();
            logger.info("Received DELETE request at /events");
            // Convert query to map
            Map<String, String> values = Utilities.queryToMap(query);
            // Check if it contains id parameter
            if(values.containsKey("id")) {
                String id = values.get("id");
                boolean successful = eventManager.deleteEvent(id);
                String responseMessage = (successful) ? "Deleted event" : "Event did not exist";
                response = new Response(HttpStatus.SC_OK, responseMessage);
            } else {
                logger.error("Malformed DELETE request received at /events {}", query);
                response = new Response(HttpStatus.SC_BAD_REQUEST, "Malformed Request");
            }
            logger.info("Sending response: {}", response);
            return response;
        }

        /**
         * Handles a POST request for the /events endpoint
         * Used for inserting a new event into the Datastore
         * @return the String that is to be used as the response
         */
        private Response handlePOST(HttpExchange t) {
            // Convert Body of the Http request to string
            String body = Utilities.toString(t.getRequestBody());
            logger.info("Received post request at /events. With body {}", body);
            eventManager.createEvent(body);
            return new Response(HttpStatus.SC_OK, "Created event successfully");
        }

        /**
         * Handles a request made to the /events endpoints and routes it according the HTTP request type
         * @param t the received Http Request
         * @throws IOException
         */
        @Override
        public void handle(HttpExchange t) throws IOException {
            // TODO: Change to respond with proper response codes
            Response response = null;
            // Get body from http request
            String requestType = getRequestType(t);
            if(HTTP_POST.equalsIgnoreCase(requestType)) {
                response = handlePOST(t);
            } else if(HTTP_GET.equalsIgnoreCase(requestType)) {
                response = handleGET(t);
            } else if(HTTP_DELETE.equalsIgnoreCase(requestType)) {
                response = handleDELETE(t);
            }
            ResponseWriter.write(t, response);
        }
    }
}
