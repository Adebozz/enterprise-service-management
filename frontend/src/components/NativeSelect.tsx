import type { ComponentProps } from 'react'
import { cn } from '@/lib/utils'

/**
 * A styled native <select>. Native selects are accessible by default (keyboard, screen readers,
 * mobile pickers); a custom listbox is only worth it when options need search or rich content.
 */
export function NativeSelect({ className, ...props }: ComponentProps<'select'>) {
  return (
    <select
      className={cn(
        'h-9 w-full rounded-md border border-input bg-transparent px-3 text-sm shadow-xs outline-none',
        'focus-visible:border-ring focus-visible:ring-[3px] focus-visible:ring-ring/50',
        'aria-invalid:border-destructive disabled:cursor-not-allowed disabled:opacity-50',
        className,
      )}
      {...props}
    />
  )
}
