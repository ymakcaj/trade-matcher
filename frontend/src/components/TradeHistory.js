import React from 'react';

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
          </tr>
        </thead>
        <tbody>
          {trades.map((trade, index) => (
            <tr key={index}>
              <td>{trade.timestamp ? new Date(trade.timestamp).toLocaleTimeString('en-GB', { hour12: false, hour: '2-digit', minute: '2-digit', second: '2-digit' }) : '—'}</td>
              <td>{trade.price ?? '—'}</td>
              <td>{trade.quantity ?? '—'}</td>
              <td>{trade.bidOrderId ?? '—'}</td>
              <td>{trade.askOrderId ?? '—'}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

export default TradeHistory;
