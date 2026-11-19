import { useEffect } from 'react';

/** The name of the shop, at the end of every tab title. */
export const SHOP_NAME = 'MicroShop';

export function useDocumentTitle(title: string): void {
  useEffect(() => {
    document.title = `${title} · ${SHOP_NAME}`;
  }, [title]);
}
