package tradeMatcher;

public final class LevelInfo {
    private final int price;
    private final int quantity;

    public LevelInfo(int price, int quantity) {
        this.price = price;
        this.quantity = quantity;
    }

    public int getPrice() {
        return price;
    }

    public int getQuantity() {
        return quantity;
    }
}
