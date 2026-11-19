import type { FormEvent, ReactNode } from 'react';
import { Button } from './Button';

/** Props for a labelled form with an explicit submit action. */
export interface FormProps {
  label: string;
  submitLabel: string;
  onSubmit: () => void;
  children: ReactNode;
}

/** A labelled form that never navigates; the caller owns the command. */
export function Form({ label, submitLabel, onSubmit, children }: FormProps) {
  return (
    <form
      aria-label={label}
      onSubmit={(event: FormEvent) => {
        event.preventDefault();
        onSubmit();
      }}
    >
      {children}
      <Button type="submit">{submitLabel}</Button>
    </form>
  );
}
