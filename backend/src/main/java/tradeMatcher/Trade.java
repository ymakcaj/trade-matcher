package tradeMatcher;

public final class Trade {
    private final TradeInfo bidTrade;
    private final TradeInfo askTrade;

    public Trade(TradeInfo bidTrade, TradeInfo askTrade) {
        this.bidTrade = bidTrade;
        this.askTrade = askTrade;
    }

    public TradeInfo getBidTrade() {
        return bidTrade;
    }

    public TradeInfo getAskTrade() {
        return askTrade;
    }
}
