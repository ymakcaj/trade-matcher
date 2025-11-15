package tradeMatcher;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import io.javalin.Javalin;
import io.javalin.http.staticfiles.Location;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.jetty.websocket.api.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Main {
    // 1. A set to hold all connected WebSocket clients
    private static final ConcurrentHashMap<Session, String> webSocketClients = new ConcurrentHashMap<>();
    private static final Gson JSON = new Gson();
    private static final Logger LOG = LoggerFactory.getLogger(Main.class);
    private static final PriceScaleRegistry PRICE_SCALES = PriceScaleProvider.getRegistry();

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

        app.before(ctx -> {
            ctx.header("Access-Control-Allow-Origin", "*");
            ctx.header("Access-Control-Allow-Headers", "Origin, Content-Type, Accept, Authorization");
            ctx.header("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        });

        app.options("/*", ctx -> {
            ctx.header("Access-Control-Allow-Origin", "*");
            ctx.header("Access-Control-Allow-Headers", "Origin, Content-Type, Accept, Authorization");
            ctx.header("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
            ctx.status(204);
        });

        // 4. Define the HTTP "Command" Endpoint
        // This is how the user SUBMITS an order
        app.post("/api/order", ctx -> {
            OrderPayload payload = ctx.bodyValidator(OrderPayload.class)
                    .check(p -> p.orderId() != null && !p.orderId().isBlank(), "orderId is required")
                    .check(p -> p.orderType() != null && !p.orderType().isBlank(), "orderType is required")
                    .check(p -> p.side() != null && !p.side().isBlank(), "side is required")
                    .check(p -> p.quantity() != null && p.quantity() > 0, "quantity must be positive")
                    .get();

            try {
        Order newOrder = toDomainOrder(payload);
        PriceScale scale = PRICE_SCALES.getScale(newOrder.getTicker());
        double displayPrice = scale.toDisplayPrice((int) Math.round(newOrder.GetPrice()));
        LOG.info("Received order via HTTP: type={}, side={}, price={}, qty={}, id={}",
            newOrder.GetOrderType(), newOrder.GetSide(), displayPrice,
            newOrder.GetInitialQuantity(), newOrder.GetOrderId());

                engine.processOrder(newOrder);
                ctx.json(Collections.singletonMap("status", "Order received"));
            } catch (IllegalArgumentException ex) {
                LOG.warn("Rejected order payload {}: {}", payload, ex.getMessage());
                ctx.status(400).json(Map.of(
                        "status", "error",
                        "message", ex.getMessage()));
            }
        });

        app.post("/api/script", ctx -> {
            String rawBody = ctx.body();
            LOG.debug("Received raw script payload: {}", rawBody);

            List<String> script;
            try {
                script = parseScriptPayload(rawBody);
            } catch (IllegalArgumentException ex) {
                LOG.warn("Rejected script payload: {}", ex.getMessage());
                ctx.status(400).json(Map.of(
                        "status", "error",
                        "message", ex.getMessage()));
                return;
            }

            LOG.info("Received script with {} commands", script.size());
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
        webSocketClients.keySet().removeIf(session -> {
            try {
                session.getRemote().sendString(payload);
                return false;
            } catch (Exception e) {
                LOG.error("Failed to send payload to client", e);
                return true;
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
        ParsedOrderAttributes scriptAttributes = resolveOrderAttributes(tokens[2], null);
        OrderSide scriptSide = parseSide(tokens[1]);
        long scriptQuantity = Long.parseLong(tokens[4]);
        double scriptPrice = Double.parseDouble(tokens[3]);
        String scriptOrderId = tokens[5];
        PriceScale scale = PRICE_SCALES.getScale("DEMO");
        int bookPrice = scriptAttributes.orderType() == OrderType.MARKET ? 0 : scale.toBookPrice(scriptPrice);
        int bookTrigger = bookPrice;
                engine.processOrder(new Order(
                        scriptOrderId,
                        "script",
                        "DEMO",
                        scriptSide,
                        scriptAttributes.orderType(),
                        scriptAttributes.timeInForce(),
                        scriptQuantity,
            bookPrice,
            bookTrigger,
                        false,
                        scriptQuantity));
                break;
            case 'M':
                if (tokens.length < 5) {
                    throw new IllegalArgumentException("Modify command requires 5 tokens");
                }
        PriceScale modifyScale = PRICE_SCALES.getScale("DEMO");
                engine.modifyOrder(
                        Long.parseLong(tokens[1]),
                        parseSide(tokens[2]),
            modifyScale.toBookPrice(Double.parseDouble(tokens[3])),
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

    private static Order toDomainOrder(OrderPayload payload) {
    ParsedOrderAttributes attributes = resolveOrderAttributes(payload.orderType(), payload.timeInForce());
    OrderType type = attributes.orderType();
    TimeInForce timeInForce = attributes.timeInForce();
    OrderSide side = parseSide(payload.side());

        Long quantityValue = payload.quantity();
        if (quantityValue == null || quantityValue <= 0) {
            throw new IllegalArgumentException("Quantity must be positive");
        }
        long quantity = quantityValue;

        double price = payload.price() != null ? payload.price() : 0.0;
        double triggerPrice = payload.triggerPrice() != null ? payload.triggerPrice() : price;

        String ticker = payload.ticker() != null && !payload.ticker().isBlank() ? payload.ticker() : "TEST";
        PriceScale scale = PRICE_SCALES.getScale(ticker);

        if ((type == OrderType.LIMIT || type == OrderType.STOP_LIMIT) && price <= 0.0) {
            throw new IllegalArgumentException("Limit orders require a positive price");
        }
        if ((type == OrderType.STOP_MARKET || type == OrderType.STOP_LIMIT) && triggerPrice <= 0.0) {
            throw new IllegalArgumentException("Stop orders require a positive trigger price");
        }
        int bookPrice = type == OrderType.MARKET ? 0 : scale.toBookPrice(price);
        int bookTriggerPrice = (type == OrderType.STOP_MARKET || type == OrderType.STOP_LIMIT)
                ? scale.toBookPrice(triggerPrice)
                : bookPrice;

        boolean postOnly = Boolean.TRUE.equals(payload.postOnly());
        long displayQuantity = payload.displayQuantity() != null ? payload.displayQuantity() : quantity;
        if (displayQuantity <= 0 || displayQuantity > quantity) {
            throw new IllegalArgumentException("displayQuantity must be between 1 and the total quantity");
        }

        String orderId = payload.orderId();
        if (orderId == null || orderId.isBlank()) {
            throw new IllegalArgumentException("orderId is required");
        }

        String userId = payload.userId() != null && !payload.userId().isBlank() ? payload.userId() : "web-client";
        String tickerResolved = ticker;

        return new Order(
                orderId,
                userId,
                tickerResolved,
                side,
                type,
                timeInForce,
                quantity,
                bookPrice,
                bookTriggerPrice,
                postOnly,
                displayQuantity);
    }

    private static OrderSide parseSide(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("Side token missing");
        }
        char c = Character.toUpperCase(token.charAt(0));
        if (c == 'B') {
            return OrderSide.BUY;
        }
        if (c == 'S') {
            return OrderSide.SELL;
        }
        throw new IllegalArgumentException("Unknown side token: " + token);
    }

    private static ParsedOrderAttributes resolveOrderAttributes(String orderTypeToken, String timeInForceToken) {
        OrderType orderType = null;
        TimeInForce timeInForce = null;

        if (orderTypeToken != null && !orderTypeToken.isBlank()) {
            String normalized = normalizeToken(orderTypeToken);
            switch (normalized) {
                case "MARKET" -> orderType = OrderType.MARKET;
                case "LIMIT" -> orderType = OrderType.LIMIT;
                case "STOPMARKET", "STOP" -> orderType = OrderType.STOP_MARKET;
                case "STOPLIMIT" -> orderType = OrderType.STOP_LIMIT;
                case "GOODTILLCANCEL", "GTC" -> {
                    orderType = OrderType.LIMIT;
                    timeInForce = TimeInForce.GTC;
                }
                case "GOODFORDAY", "DAY" -> {
                    orderType = OrderType.LIMIT;
                    timeInForce = TimeInForce.DAY;
                }
                case "FILLANDKILL", "FAK", "IOC" -> {
                    orderType = OrderType.LIMIT;
                    timeInForce = TimeInForce.IOC;
                }
                case "FILLORKILL", "FOK" -> {
                    orderType = OrderType.LIMIT;
                    timeInForce = TimeInForce.FOK;
                }
                default -> throw new IllegalArgumentException("Unsupported order type token '" + orderTypeToken + "'");
            }
        }

        TimeInForce tifFromToken = parseTimeInForceToken(timeInForceToken);
        if (tifFromToken != null) {
            timeInForce = tifFromToken;
        }

        if (orderType == null) {
            orderType = OrderType.LIMIT;
        }

        if (timeInForce == null) {
            timeInForce = orderType == OrderType.MARKET ? TimeInForce.IOC : TimeInForce.GTC;
        }

        return new ParsedOrderAttributes(orderType, timeInForce);
    }

    private static TimeInForce parseTimeInForceToken(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        String normalized = normalizeToken(token);
        return switch (normalized) {
            case "GTC", "GOODTILLCANCEL" -> TimeInForce.GTC;
            case "DAY", "GFD", "GOODFORDAY" -> TimeInForce.DAY;
            case "IOC", "FAK", "FILLANDKILL" -> TimeInForce.IOC;
            case "FOK", "FILLORKILL" -> TimeInForce.FOK;
            default -> throw new IllegalArgumentException("Unsupported time-in-force token '" + token + "'");
        };
    }

    private static String normalizeToken(String token) {
    return token.replace("_", "").replace("-", "").replace(" ", "").toUpperCase();
    }

    private record ParsedOrderAttributes(OrderType orderType, TimeInForce timeInForce) {
    }

    private static List<String> parseScriptPayload(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            return List.of();
        }

        JsonElement root;
        try {
            root = JSON.fromJson(rawBody, JsonElement.class);
        } catch (JsonSyntaxException ex) {
            throw new IllegalArgumentException("Body must be valid JSON");
        }

        if (root == null) {
            return List.of();
        }

        if (root.isJsonArray()) {
            return extractCommandsFromArray(root.getAsJsonArray());
        }

        if (root.isJsonObject()) {
            JsonObject object = root.getAsJsonObject();
            if (object.has("commands")) {
                JsonElement commandsElement = object.get("commands");
                if (!commandsElement.isJsonArray()) {
                    throw new IllegalArgumentException("'commands' property must be an array");
                }
                return extractCommandsFromArray(commandsElement.getAsJsonArray());
            }
        }

        throw new IllegalArgumentException("Script payload must be an array of command strings");
    }

    private static List<String> extractCommandsFromArray(JsonArray array) {
        List<String> commands = new ArrayList<>(array.size());
        for (JsonElement element : array) {
            if (element == null || element.isJsonNull()) {
                continue;
            }
            if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
                commands.add(element.getAsString());
                continue;
            }
            if (element.isJsonObject()) {
                JsonObject obj = element.getAsJsonObject();
                if (obj.has("command") && obj.get("command").isJsonPrimitive()) {
                    commands.add(obj.get("command").getAsString());
                    continue;
                }
                if (obj.has("value") && obj.get("value").isJsonPrimitive()) {
                    commands.add(obj.get("value").getAsString());
                    continue;
                }
            }
            throw new IllegalArgumentException("Unsupported script entry: " + element);
        }
        return commands;
    }

    private record OrderPayload(
        String orderId,
        String userId,
        String ticker,
        String orderType,
        String timeInForce,
        String side,
        Double price,
        Double triggerPrice,
        Long quantity,
        Boolean postOnly,
        Long displayQuantity) {
    }
}
