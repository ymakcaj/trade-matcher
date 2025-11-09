import React from 'react';

function OrderBook({ orderBook }) {
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
            {orderBook.bids.map((bid, index) => (
              <tr key={index}>
                <td>{bid.price}</td>
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
            {orderBook.asks.map((ask, index) => (
              <tr key={index}>
                <td>{ask.price}</td>
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
