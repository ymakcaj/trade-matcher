import React from 'react';

const formatTime = (timestamp) => {
  if (!timestamp) {
    return '—';
  }
  return new Date(timestamp).toLocaleTimeString('en-GB', {
    hour12: false,
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit'
  });
};

function TradeHistory({ trades }) {
  return (
    <div className="trade-history">
      <h3>Trade History</h3>
      <table>
        <thead>
          <tr>
            <th>Time</th>
            <th>Price</th>
            <th>Quantity</th>
            <th>Buy Order</th>
            <th>Sell Order</th>
            <th>Buy User</th>
            <th>Sell User</th>
            <th>Your Role</th>
          </tr>
        </thead>
        <tbody>
          {trades.map((trade, index) => (
            <tr key={trade.id ?? index} className={trade.viewerRole ? `trade-own trade-${trade.viewerRole}` : ''}>
              <td>{formatTime(trade.timestamp)}</td>
              <td>{typeof trade.price === 'number' ? trade.price.toFixed(3) : trade.price ?? '—'}</td>
              <td>{trade.quantity ?? '—'}</td>
              <td>{trade.bidOrderId ?? '—'}</td>
              <td>{trade.askOrderId ?? '—'}</td>
              <td>{trade.bidUserId ?? '—'}</td>
              <td>{trade.askUserId ?? '—'}</td>
              <td>{trade.viewerRole ? trade.viewerRole.toUpperCase() : '—'}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

export default TradeHistory;
