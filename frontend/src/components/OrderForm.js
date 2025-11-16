import React, { useMemo, useState } from 'react';

const ORDER_TYPES = [
  { value: 'LIMIT', label: 'Limit' },
  { value: 'MARKET', label: 'Market' },
  { value: 'STOP_MARKET', label: 'Stop Market' },
  { value: 'STOP_LIMIT', label: 'Stop Limit' }
];

const TIFS = [
  { value: 'GTC', label: 'GTC' },
  { value: 'DAY', label: 'Day' },
  { value: 'IOC', label: 'IOC' },
  { value: 'FOK', label: 'FOK' }
];

function OrderForm({ onSubmitOrder, disabled, defaultTicker = 'TEST' }) {
  const [formState, setFormState] = useState({
    orderId: '',
    ticker: defaultTicker,
    side: 'BUY',
    orderType: 'LIMIT',
    timeInForce: 'GTC',
    price: '',
    triggerPrice: '',
    quantity: '',
    displayQuantity: '',
    postOnly: false
  });

  const allowPrice = useMemo(() => formState.orderType !== 'MARKET', [formState.orderType]);
  const supportsPostOnly = useMemo(() => formState.orderType === 'LIMIT', [formState.orderType]);
  const requiresTrigger = useMemo(
    () => formState.orderType === 'STOP_MARKET' || formState.orderType === 'STOP_LIMIT',
    [formState.orderType]
  );

  const handleChange = (field) => (event) => {
    const value = field === 'postOnly' ? event.target.checked : event.target.value;
    setFormState((prev) => {
      if (field === 'orderType' && value !== 'LIMIT' && prev.postOnly) {
        return { ...prev, [field]: value, postOnly: false };
      }
      return { ...prev, [field]: value };
    });
  };

  const handleSubmit = (event) => {
    event.preventDefault();
    const orderId = formState.orderId.trim();
    const ticker = formState.ticker.trim().toUpperCase() || defaultTicker;
    if (!orderId) {
      return;
    }
    const quantity = parseInt(formState.quantity, 10);
    if (!Number.isFinite(quantity) || quantity <= 0) {
      return;
    }
    const displayQuantity = formState.displayQuantity ? parseInt(formState.displayQuantity, 10) : quantity;
    const safeDisplayQuantity = Number.isFinite(displayQuantity) && displayQuantity > 0 ? displayQuantity : quantity;

    const payload = {
      orderId,
      ticker,
      orderType: formState.orderType,
      timeInForce: formState.timeInForce,
      side: formState.side,
      quantity,
  postOnly: supportsPostOnly && formState.postOnly,
      displayQuantity: safeDisplayQuantity,
      price: allowPrice && formState.price !== '' ? Number(formState.price) : null,
      triggerPrice: requiresTrigger && formState.triggerPrice !== '' ? Number(formState.triggerPrice) : null
    };

    onSubmitOrder(payload);
    setFormState((prev) => ({
      ...prev,
      orderId: '',
      price: '',
      triggerPrice: '',
      quantity: '',
      displayQuantity: ''
    }));
  };

  return (
    <form onSubmit={handleSubmit} className="order-form">
      <h3>Submit Order</h3>
      <div className="form-row">
        <label htmlFor="order-id">Order ID</label>
        <input
          id="order-id"
          type="text"
          value={formState.orderId}
          onChange={handleChange('orderId')}
          placeholder="Unique identifier"
          required
          disabled={disabled}
        />
      </div>
      <div className="form-row">
        <label htmlFor="ticker">Ticker</label>
        <input
          id="ticker"
          type="text"
          value={formState.ticker}
          onChange={handleChange('ticker')}
          placeholder="Symbol"
          disabled={disabled}
        />
      </div>
      <div className="form-row two-column">
        <label htmlFor="side">Side</label>
        <select id="side" value={formState.side} onChange={handleChange('side')} disabled={disabled}>
          <option value="BUY">Buy</option>
          <option value="SELL">Sell</option>
        </select>
        <label htmlFor="order-type">Type</label>
        <select id="order-type" value={formState.orderType} onChange={handleChange('orderType')} disabled={disabled}>
          {ORDER_TYPES.map((type) => (
            <option key={type.value} value={type.value}>{type.label}</option>
          ))}
        </select>
      </div>
      <div className="form-row two-column">
        <label htmlFor="tif">Time in force</label>
        <select id="tif" value={formState.timeInForce} onChange={handleChange('timeInForce')} disabled={disabled}>
          {TIFS.map((tif) => (
            <option key={tif.value} value={tif.value}>{tif.label}</option>
          ))}
        </select>
        <label className="post-only">
          <input
            type="checkbox"
            checked={formState.postOnly}
            onChange={handleChange('postOnly')}
            disabled={disabled || !supportsPostOnly}
          />
          Post only
        </label>
      </div>
      {allowPrice && (
        <div className="form-row">
          <label htmlFor="price">Price</label>
          <input
            id="price"
            type="number"
            step="0.001"
            value={formState.price}
            onChange={handleChange('price')}
            placeholder="Price"
            min="0"
            disabled={disabled}
          />
        </div>
      )}
      {requiresTrigger && (
        <div className="form-row">
          <label htmlFor="trigger-price">Trigger price</label>
          <input
            id="trigger-price"
            type="number"
            step="0.001"
            value={formState.triggerPrice}
            onChange={handleChange('triggerPrice')}
            placeholder="Trigger price"
            min="0"
            disabled={disabled}
            required={requiresTrigger}
          />
        </div>
      )}
      <div className="form-row two-column">
        <label htmlFor="quantity">Quantity</label>
        <input
          id="quantity"
          type="number"
          value={formState.quantity}
          onChange={handleChange('quantity')}
          placeholder="Total quantity"
          min="1"
          required
          disabled={disabled}
        />
        <label htmlFor="display-quantity">Display quantity</label>
        <input
          id="display-quantity"
          type="number"
          value={formState.displayQuantity}
          onChange={handleChange('displayQuantity')}
          placeholder="Visible quantity"
          min="1"
          disabled={disabled}
        />
      </div>
      <button type="submit" disabled={disabled}>Submit Order</button>
    </form>
  );
}

export default OrderForm;
