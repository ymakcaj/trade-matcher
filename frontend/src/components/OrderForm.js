import React, { useState } from 'react';

function OrderForm({ onSubmitOrder }) {
  const [side, setSide] = useState('Buy');
  const [orderType, setOrderType] = useState('LIMIT');
  const [price, setPrice] = useState('');
  const [quantity, setQuantity] = useState('');
  const [orderId, setOrderId] = useState('');


  const handleSubmit = (e) => {
    e.preventDefault();
    onSubmitOrder({ 
        orderId: parseInt(orderId),
        side, 
        orderType,
        price: parseInt(price), 
        quantity: parseInt(quantity)
    });
    setPrice('');
    setQuantity('');
    setOrderId('');
  };

  return (
    <form onSubmit={handleSubmit} className="order-form">
      <h3>Create Order</h3>
      <input 
        type="number" 
        value={orderId} 
        onChange={(e) => setOrderId(e.target.value)} 
        placeholder="Order ID" 
        required
      />
      <select value={side} onChange={(e) => setSide(e.target.value)}>
        <option value="Buy">Buy</option>
        <option value="Sell">Sell</option>
      </select>
      <select value={orderType} onChange={(e) => setOrderType(e.target.value)}>
        <option value="LIMIT">LIMIT</option>
        <option value="MARKET">MARKET</option>
        <option value="FILL_OR_KILL">FILL_OR_KILL</option>
      </select>
      <input 
        type="number" 
        value={price} 
        onChange={(e) => setPrice(e.target.value)} 
        placeholder="Price" 
        required
      />
      <input 
        type="number" 
        value={quantity} 
        onChange={(e) => setQuantity(e.target.value)} 
        placeholder="Quantity" 
        required
      />
      <button type="submit">Submit Order</button>
    </form>
  );
}

export default OrderForm;
