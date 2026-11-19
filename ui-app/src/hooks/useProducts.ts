import { useEffect, useState } from 'react';
import { fetchProductBySku } from '../api/products';
import type { Product } from '../api/types';

// The cart and the orders only know a product by its SKU, name and price. To show a picture (and the list
// price) they ask the public product endpoint, once per SKU, and remember the answer for as long as the
// page stays open. A lookup that fails is simply not remembered: the line is shown without a picture, and
// the next visit to the page tries again.

const known = new Map<string, Product>();
const inFlight = new Map<string, Promise<void>>();

/** For tests: forget every product that was looked up. */
export function clearProductLookupCache(): void {
  known.clear();
  inFlight.clear();
}

/**
 * Products by SKU. A SKU that is not known (yet, or because the lookup failed) is missing from the
 * answer, and the component is drawn again when more arrive.
 */
export function useProductsBySku(skus: readonly string[]): Record<string, Product | undefined> {
  const [, setVersion] = useState(0);
  const key = skus.join('\n');

  useEffect(() => {
    let live = true;
    const wanted = key === '' ? [] : key.split('\n');
    for (const sku of wanted) {
      if (known.has(sku) || inFlight.has(sku)) continue;
      const lookup = fetchProductBySku(sku).then(
        (product) => {
          known.set(sku, product);
        },
        () => {
          // Not found, or the service is down. The caller copes with a missing product.
        },
      );
      inFlight.set(sku, lookup);
      void lookup.finally(() => inFlight.delete(sku));
    }
    const pending = wanted
      .map((sku) => inFlight.get(sku))
      .filter((promise): promise is Promise<void> => promise !== undefined);
    if (pending.length > 0) {
      void Promise.all(pending).then(() => {
        if (live) setVersion((version) => version + 1);
      });
    }
    return () => {
      live = false;
    };
  }, [key]);

  const result: Record<string, Product | undefined> = {};
  for (const sku of skus) result[sku] = known.get(sku);
  return result;
}
