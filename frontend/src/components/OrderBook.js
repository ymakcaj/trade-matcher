import React from 'react';

const formatPrice = (value) => (typeof value === 'number' ? value.toFixed(3) : value);

function OrderBook({ orderBook, depth = 20 }) {
  const bids = orderBook.bids.slice(0, depth);
  const asks = orderBook.asks.slice(0, depth);

  return (
    <div className="order-book">
      <h3>Order Book</h3>
      <div className="side">
        <h4>Bids</h4>
        <table>
          <thead>
            <tr>
              <th>Price</th>
              <th>Quantity</th>
            </tr>
          </thead>
          <tbody>
            {bids.map((bid, index) => (
              <tr key={`bid-${bid.price}-${index}`} className="side-buy">
                <td>{formatPrice(bid.price)}</td>
                <td>{bid.quantity}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <div className="side">
        <h4>Asks</h4>
        <table>
          <thead>
            <tr>
              <th>Price</th>
              <th>Quantity</th>
            </tr>
          </thead>
          <tbody>
            {asks.map((ask, index) => (
              <tr key={`ask-${ask.price}-${index}`} className="side-sell">
                <td>{formatPrice(ask.price)}</td>
                <td>{ask.quantity}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}

export default OrderBook;
