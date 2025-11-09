package tradeMatcher;

import java.util.List;

public final class OrderbookLevelInfos {
    private final List<LevelInfo> bids;
    private final List<LevelInfo> asks;

    public OrderbookLevelInfos(List<LevelInfo> bids, List<LevelInfo> asks) {
        this.bids = List.copyOf(bids);
        this.asks = List.copyOf(asks);
    }

    public List<LevelInfo> GetBids() {
        return bids;
    }

    public List<LevelInfo> GetAsks() {
        return asks;
    }
}
