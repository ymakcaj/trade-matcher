package tradeMatcher;

import io.javalin.Javalin;
import io.javalin.http.staticfiles.Location;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.jetty.websocket.api.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Main {
    // 1. A set to hold all connected WebSocket clients
    private static final ConcurrentHashMap<Session, String> webSocketClients = new ConcurrentHashMap<>();
    private static final Logger LOG = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        // This is your matching engine!
        MatchingEngine engine = new MatchingEngine();

        // 2. Set up a "listener" on your engine.
        // When your engine has an update, it will call this lambda.
        // This is the "bridge" between your engine and your UI.
        engine.onOrderBookUpdate(orderBookJson -> broadcastToClients(orderBookJson));

        engine.onTrades(tradesJson -> broadcastToClients(tradesJson));

        int port = resolvePort();

        // 3. Start the Javalin server
        Javalin app = Javalin.create(config -> {
            config.plugins.enableCors(cors ->
                    cors.add(it -> it.anyHost()));

            if (Main.class.getResource("/public") != null) {
                // Serve pre-built frontend assets when they are bundled into the jar
                config.staticFiles.add("/public");
            } else {
                // Fallback for local development: point to the React build output
                Path frontendBuild = Paths.get("..", "frontend", "build").toAbsolutePath().normalize();
                if (Files.exists(frontendBuild)) {
                    config.staticFiles.add(staticFiles -> {
                        staticFiles.directory = frontendBuild.toString();
                        staticFiles.location = Location.EXTERNAL;
                    });
                }
            }
        }).start("0.0.0.0", port);

        // 4. Define the HTTP "Command" Endpoint
        // This is how the user SUBMITS an order
        app.post("/api/order", ctx -> {
            Order newOrder = ctx.bodyAsClass(Order.class);
            LOG.info("Received order via HTTP: type={}, side={}, price={}, qty={}, id={}",
                    newOrder.GetOrderType(), newOrder.GetSide(), newOrder.GetPrice(),
                    newOrder.GetInitialQuantity(), newOrder.GetOrderId());
            
            // Send the order to your engine for processing
            engine.processOrder(newOrder); 
            
            ctx.json(Collections.singletonMap("status", "Order received"));
        });

        app.post("/api/script", ctx -> {
            String[] script = ctx.bodyAsClass(String[].class);
            LOG.info("Received script with {} commands", script.length);
            int executed = 0;
            for (String command : script) {
                if (command == null || command.isBlank()) {
                    continue;
                }
                String trimmed = command.trim();
                LOG.info("Executing script line: {}", trimmed);
                try {
                    executeScriptLine(engine, trimmed);
                    executed++;
                } catch (IllegalArgumentException ex) {
                    LOG.warn("Failed to execute script line '{}': {}", trimmed, ex.getMessage());
                    ctx.status(400).json(Map.of(
                            "status", "error",
                            "message", ex.getMessage(),
                            "line", trimmed));
                    return;
                }
            }
            ctx.json(Map.of(
                    "status", "Script executed",
                    "commandsProcessed", executed));
        });

        app.post("/api/reset", ctx -> {
            LOG.info("Received reset request");
            engine.reset();
            ctx.json(Map.of("status", "Reset complete"));
        });

        // 5. Define the WebSocket "Data" Endpoint
        // This is how the user OBSERVES the market
        app.ws("/ws/orderbook", ws -> {
            ws.onConnect(ctx -> {
                System.out.println("Client connected");
                webSocketClients.put(ctx.session, "user");
                
                // When a new client connects, send them the current state
                ctx.send(engine.getCurrentOrderBookAsJson()); 
            });

            ws.onMessage(ctx -> {
                // We don't expect messages, but you could add them
            });

            ws.onClose(ctx -> {
                System.out.println("Client disconnected");
                webSocketClients.remove(ctx.session);
            });
        });
    }

    private static void broadcastToClients(String payload) {
        webSocketClients.keySet().forEach(session -> {
            try {
                session.getRemote().sendString(payload);
            } catch (Exception e) {
                LOG.error("Failed to send payload to client", e);
            }
        });
    }

    private static int resolvePort() {
        String envPort = System.getenv("PORT");
        if (envPort != null && !envPort.isBlank()) {
            try {
                return Integer.parseInt(envPort.trim());
            } catch (NumberFormatException ex) {
                LOG.warn("Invalid PORT environment value '{}', falling back to 7070", envPort);
            }
        }
        return 7070;
    }

    private static void executeScriptLine(MatchingEngine engine, String command) {
        String[] tokens = command.split("\\s+");
        if (tokens.length == 0) {
            return;
        }
        char code = Character.toUpperCase(tokens[0].charAt(0));
        switch (code) {
            case 'A':
                if (tokens.length < 6) {
                    throw new IllegalArgumentException("Add command requires 6 tokens");
                }
        engine.processOrder(new Order(
            parseOrderType(tokens[2]),
                        Long.parseLong(tokens[5]),
                        parseSide(tokens[1]),
                        Integer.parseInt(tokens[3]),
                        Integer.parseInt(tokens[4])));
                break;
            case 'M':
                if (tokens.length < 5) {
                    throw new IllegalArgumentException("Modify command requires 5 tokens");
                }
                engine.modifyOrder(
                        Long.parseLong(tokens[1]),
                        parseSide(tokens[2]),
                        Integer.parseInt(tokens[3]),
                        Integer.parseInt(tokens[4]));
                break;
            case 'R':
            case 'C':
                if (tokens.length < 2) {
                    throw new IllegalArgumentException("Cancel command requires an order id");
                }
                engine.cancelOrder(Long.parseLong(tokens[1]));
                break;
            default:
                throw new IllegalArgumentException("Unsupported script code '" + code + "'");
        }
    }

    private static Side parseSide(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("Side token missing");
        }
        char c = Character.toUpperCase(token.charAt(0));
        if (c == 'B') {
            return Side.Buy;
        }
        if (c == 'S') {
            return Side.Sell;
        }
        throw new IllegalArgumentException("Unknown side token: " + token);
    }

    private static OrderType parseOrderType(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("Order type token missing");
        }
        String normalized = token.replace("_", "").replace("-", "").toUpperCase();
        return switch (normalized) {
            case "GTC", "LIMIT", "GOODTILLCANCEL" -> OrderType.GoodTillCancel;
            case "FOK", "FILLORKILL" -> OrderType.FillOrKill;
            case "FAK", "FILLANDKILL" -> OrderType.FillAndKill;
            case "GFD", "GOODFORDAY" -> OrderType.GoodForDay;
            case "MARKET" -> OrderType.Market;
            default -> OrderType.valueOf(token);
        };
    }

}
