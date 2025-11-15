package tradeMatcher;

import tradeMatcher.Order;
import tradeMatcher.OrderModify;
import tradeMatcher.OrderSide;
import tradeMatcher.OrderType;
import tradeMatcher.TimeInForce;
import tradeMatcher.Orderbook;
import tradeMatcher.OrderbookLevelInfos;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class OrderbookTests {

    private enum ActionType {
        Add,
        Cancel,
        Modify
    }

    private record Information(ActionType type, OrderType orderType, TimeInForce timeInForce, OrderSide side, double price, int quantity, long orderId) {
    }

    private record Result(int allCount, int bidCount, int askCount) {
    }

    private record ParsedData(List<Information> actions, Result result) {
    }

    private static final class InputHandler {

        ParsedData getInformations(String fileName) {
            List<Information> actions = new ArrayList<>(64);
            Result result = null;

            try (BufferedReader reader = openReader(fileName)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isEmpty()) {
                        break;
                    }

                    line = line.trim();
                    if (line.isEmpty()) {
                        continue;
                    }

                    char code = line.charAt(0);
                    if (code == 'R') {
                        if (result != null) {
                            throw new IllegalStateException("Result should only be specified once.");
                        }
                        result = parseResult(line);
                        ensureNoContentRemains(reader);
                        break;
                    }

                    actions.add(parseInformation(line));
                }
            } catch (IOException ex) {
                throw new IllegalStateException("Failed to read test file.", ex);
            }

            if (result == null) {
                throw new IllegalStateException("No result specified.");
            }

            return new ParsedData(List.copyOf(actions), result);
        }

        private BufferedReader openReader(String fileName) {
            String resource = "/TestFiles/" + fileName;
            InputStream stream = OrderbookTests.class.getResourceAsStream(resource);
            if (stream == null) {
                throw new IllegalArgumentException("Missing test file: " + resource);
            }
            return new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
        }

        private void ensureNoContentRemains(BufferedReader reader) throws IOException {
            String trailing;
            while ((trailing = reader.readLine()) != null) {
                if (!trailing.trim().isEmpty()) {
                    throw new IllegalStateException("Result should only be specified at the end.");
                }
            }
        }

        private Information parseInformation(String line) {
            String[] tokens = split(line);
            char code = Character.toUpperCase(tokens[0].charAt(0));

            if (code == 'A') {
                OrderSide side = parseSide(tokens[1]);
                ParsedOrderSpec spec = parseOrderSpec(tokens[2]);
                double price = parsePrice(tokens[3]);
                int quantity = parseQuantity(tokens[4]);
                long orderId = parseOrderId(tokens[5]);
                return new Information(ActionType.Add, spec.orderType(), spec.timeInForce(), side, price, quantity, orderId);
            }

            if (code == 'M') {
                long orderId = parseOrderId(tokens[1]);
                OrderSide side = parseSide(tokens[2]);
                double price = parsePrice(tokens[3]);
                int quantity = parseQuantity(tokens[4]);
                return new Information(ActionType.Modify, null, null, side, price, quantity, orderId);
            }

            if (code == 'C') {
                long orderId = parseOrderId(tokens[1]);
                return new Information(ActionType.Cancel, null, null, null, 0, 0, orderId);
            }

            throw new IllegalStateException("Unsupported action type: " + tokens[0]);
        }

        private Result parseResult(String line) {
            String[] tokens = split(line);
            if (tokens.length < 4) {
                throw new IllegalStateException("Invalid result line: " + line);
            }

            int allCount = parseQuantity(tokens[1]);
            int bidCount = parseQuantity(tokens[2]);
            int askCount = parseQuantity(tokens[3]);
            return new Result(allCount, bidCount, askCount);
        }

        private String[] split(String line) {
            String[] tokens = line.split("\\s+");
            if (tokens.length == 0) {
                throw new IllegalStateException("Invalid input line: " + line);
            }
            return tokens;
        }

        private OrderSide parseSide(String token) {
            String normalized = token.toUpperCase(Locale.ROOT);
            return switch (normalized) {
                case "B" -> OrderSide.BUY;
                case "S" -> OrderSide.SELL;
                default -> throw new IllegalStateException("Unknown side: " + token);
            };
        }

        private ParsedOrderSpec parseOrderSpec(String token) {
            if (token == null || token.isBlank()) {
                throw new IllegalStateException("Order type token is required");
            }
            String normalized = token.replace("_", "")
                    .replace("-", "")
                    .replace(" ", "")
                    .toUpperCase(Locale.ROOT);
            return switch (normalized) {
                case "MARKET" -> new ParsedOrderSpec(OrderType.MARKET, TimeInForce.IOC);
                case "LIMIT" -> new ParsedOrderSpec(OrderType.LIMIT, TimeInForce.GTC);
                case "STOPMARKET", "STOP" -> new ParsedOrderSpec(OrderType.STOP_MARKET, TimeInForce.IOC);
                case "STOPLIMIT" -> new ParsedOrderSpec(OrderType.STOP_LIMIT, TimeInForce.GTC);
                case "GOODTILLCANCEL", "GTC" -> new ParsedOrderSpec(OrderType.LIMIT, TimeInForce.GTC);
                case "GOODFORDAY", "DAY" -> new ParsedOrderSpec(OrderType.LIMIT, TimeInForce.DAY);
                case "FILLANDKILL", "IOC", "FAK" -> new ParsedOrderSpec(OrderType.LIMIT, TimeInForce.IOC);
                case "FILLORKILL", "FOK" -> new ParsedOrderSpec(OrderType.LIMIT, TimeInForce.FOK);
                default -> throw new IllegalStateException("Unsupported order type token: " + token);
            };
        }

        private double parsePrice(String token) {
            try {
                return Double.parseDouble(token);
            } catch (NumberFormatException ex) {
                throw new IllegalStateException("Invalid price: " + token, ex);
            }
        }

        private int parseQuantity(String token) {
            return toInt(token, "quantity");
        }

        private long parseOrderId(String token) {
            long value = parseUnsignedLong(token);
            if (value < 0) {
                throw new IllegalStateException("Order id must be non-negative: " + token);
            }
            return value;
        }

        private int toInt(String token, String label) {
            long value = parseUnsignedLong(token);
            if (value > Integer.MAX_VALUE) {
                throw new IllegalStateException("Value too large for " + label + ": " + token);
            }
            return (int) value;
        }

        private long parseUnsignedLong(String token) {
            Objects.requireNonNull(token, "token");
            if (token.isEmpty()) {
                throw new IllegalStateException("Empty numeric token");
            }
            long value = Long.parseLong(token);
            if (value < 0) {
                throw new IllegalStateException("Value must be non-negative: " + token);
            }
            return value;
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Match_GoodTillCancel.txt",
            "Match_FillAndKill.txt",
            "Match_FillOrKill_Hit.txt",
            "Match_FillOrKill_Miss.txt",
            "Cancel_Success.txt",
        "Modify_Side.txt",
        "Match_Market.txt",
    })
    void orderbookTestSuite(String fileName) {
        InputHandler handler = new InputHandler();
        ParsedData parsed = handler.getInformations(fileName);

        try (Orderbook orderbook = new Orderbook()) {
            for (Information action : parsed.actions()) {
                switch (action.type()) {
            case Add -> {
            OrderType orderType = Objects.requireNonNull(action.orderType(), "Missing order type");
            TimeInForce timeInForce = Objects.requireNonNull(action.timeInForce(), "Missing time in force");
            PriceScale scale = PriceScaleProvider.getRegistry().getScale("TEST");
            int bookPrice = orderType == OrderType.MARKET ? 0 : scale.toBookPrice(action.price());
            int bookTrigger = (orderType == OrderType.STOP_MARKET || orderType == OrderType.STOP_LIMIT)
                ? scale.toBookPrice(action.price())
                : bookPrice;

            orderbook.AddOrder(new Order(
                String.valueOf(action.orderId()),
                "unit-test",
                "TEST",
                Objects.requireNonNull(action.side(), "Missing side"),
                orderType,
                timeInForce,
                action.quantity(),
                bookPrice,
                bookTrigger,
                false,
                action.quantity()));
            }
                    case Modify -> orderbook.ModifyOrder(new OrderModify(
                            action.orderId(),
                Objects.requireNonNull(action.side(), "Missing side"),
                PriceScaleProvider.getRegistry().getScale("TEST").toBookPrice(action.price()),
                            action.quantity()));
                    case Cancel -> orderbook.CancelOrder(action.orderId());
                    default -> throw new IllegalStateException("Unsupported action: " + action.type());
                }

                        // Removed misplaced record declaration
            }

            OrderbookLevelInfos infos = orderbook.GetOrderInfos();
            Result expected = parsed.result();

            Assertions.assertEquals(expected.allCount(), orderbook.Size(), "Unexpected order count");
            Assertions.assertEquals(expected.bidCount(), infos.GetBids().size(), "Unexpected bid count");
            Assertions.assertEquals(expected.askCount(), infos.GetAsks().size(), "Unexpected ask count");
        }
    }
    
        private record ParsedOrderSpec(OrderType orderType, TimeInForce timeInForce) {
        }
}
