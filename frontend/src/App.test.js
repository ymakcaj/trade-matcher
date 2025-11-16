import { render, screen } from '@testing-library/react';
import App from './App';

test('renders application shell', () => {
  render(<App />);
  expect(screen.getByRole('heading', { level: 1, name: /trade tester/i })).toBeInTheDocument();
  expect(screen.getByLabelText(/api token/i)).toBeInTheDocument();
  expect(screen.getByRole('button', { name: /reset/i })).toBeDisabled();
});
