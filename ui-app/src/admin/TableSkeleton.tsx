/** Grey bars where a table will be, while its first page of data is on the way. */
export function TableSkeleton({ rows = 6, columns, label }: { rows?: number; columns: number; label: string }) {
  return (
    <div className="table-wrap table-skeleton" role="status" aria-label={label}>
      <span className="sr-only">{label}</span>
      {Array.from({ length: rows }, (_, row) => (
        <div className="table-skeleton-row" key={row} aria-hidden="true">
          {Array.from({ length: columns }, (_, column) => (
            <span className="skeleton skeleton--cell" key={column} />
          ))}
        </div>
      ))}
    </div>
  );
}
