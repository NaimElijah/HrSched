import { useId } from 'react';
import type { ReactNode } from 'react';

/**
 * A table in a focusable scroll region named by the table's own caption (#257, C8).
 *
 * The region takes focus so a table wider than the screen can be scrolled from the keyboard, and
 * it is named by the caption so a screen reader says what it is on arriving. The caption is
 * visually hidden because the screen's heading already shows what the table holds.
 *
 * @param caption names both the table and its region
 * @param children the table's head and body
 */
export function TableRegion({ caption, children }: { caption: string; children: ReactNode }) {
  const captionId = useId();
  return (
    <div className="table-scroll" role="region" aria-labelledby={captionId} tabIndex={0}>
      <table>
        <caption id={captionId} className="visually-hidden">
          {caption}
        </caption>
        {children}
      </table>
    </div>
  );
}
