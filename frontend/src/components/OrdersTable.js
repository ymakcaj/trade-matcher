import React from 'react';

function OrdersTable({ orders }) {
  return (
    <div className="orders-table">
      <h3>Open Orders</h3>
      {(!orders || orders.length === 0) ? (
        <p className="empty">No open orders.</p>
      ) : (
        <table>
          <thead>
            <tr>
              <th>ID</th>
              <th>Ticker</th>
              <th>Side</th>
              <th>Type</th>
              <th>Price</th>
              <th>Quantity</th>
            </tr>
          </thead>
          <tbody>
            {orders.map((order) => (
              <tr key={order.orderId}>
                <td>{order.orderId}</td>
                <td>{order.ticker}</td>
                <td className={`side-${order.side?.toLowerCase()}`}>{order.side}</td>
                <td>{order.orderType}</td>
                <td>{typeof order.price === 'number' ? order.price.toFixed(3) : order.price}</td>
                <td>{order.quantity}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}

export default OrdersTable;
