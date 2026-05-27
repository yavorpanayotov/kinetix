import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { FormLabelledInput } from './FormLabelledInput'

describe('FormLabelledInput', () => {
  it('renders an input with the matching label', () => {
    render(<FormLabelledInput label="Symbol" defaultValue="AAPL" />)
    const input = screen.getByLabelText('Symbol')
    expect(input).toBeInTheDocument()
    expect(input).toHaveValue('AAPL')
  })

  it('associates the label and input via htmlFor / id', () => {
    render(<FormLabelledInput label="Quantity" />)
    const input = screen.getByLabelText('Quantity')
    const id = input.getAttribute('id')
    expect(id).toBeTruthy()
    const label = document.querySelector(`label[for="${id}"]`)
    expect(label).not.toBeNull()
    expect(label).toHaveTextContent('Quantity')
  })

  it('honours a caller-supplied id over the generated one', () => {
    render(<FormLabelledInput label="Comment" id="my-comment" />)
    const input = screen.getByLabelText('Comment')
    expect(input).toHaveAttribute('id', 'my-comment')
    const label = document.querySelector('label[for="my-comment"]')
    expect(label).not.toBeNull()
  })

  it('renders an aria-described hint when supplied', () => {
    render(<FormLabelledInput label="Symbol" hint="Three letters" />)
    const input = screen.getByLabelText('Symbol')
    const describedBy = input.getAttribute('aria-describedby')
    expect(describedBy).toBeTruthy()
    const hint = document.getElementById(describedBy!)
    expect(hint).toHaveTextContent('Three letters')
  })

  it('omits aria-describedby when no hint is supplied', () => {
    render(<FormLabelledInput label="Side" />)
    const input = screen.getByLabelText('Side')
    expect(input).not.toHaveAttribute('aria-describedby')
  })

  it('forwards arbitrary input props (placeholder, type, etc.) onto the input', () => {
    render(<FormLabelledInput label="Notional" type="number" placeholder="0.00" />)
    const input = screen.getByLabelText('Notional')
    expect(input).toHaveAttribute('type', 'number')
    expect(input).toHaveAttribute('placeholder', '0.00')
  })

  it('generates distinct ids across multiple instances', () => {
    render(
      <div>
        <FormLabelledInput label="Symbol" />
        <FormLabelledInput label="Quantity" />
      </div>,
    )
    const symbol = screen.getByLabelText('Symbol').getAttribute('id')
    const qty = screen.getByLabelText('Quantity').getAttribute('id')
    expect(symbol).not.toBe(qty)
  })
})
