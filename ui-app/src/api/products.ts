import { getJson, withQuery, type RequestOptions } from './client';
import type { Page, Product, ProductDetail } from './types';

/** The orders the list can be sorted in. Every one of them is something the product API really does. */
export type SortKey = 'price-asc' | 'price-desc' | 'newest';

interface SortParams {
  sortBy: string;
  sortDir: 'asc' | 'desc';
}

/** The API sorts by any field of a product. "Newest" is the highest id: products are numbered as they are added. */
export const SORT_PARAMS: Record<SortKey, SortParams> = {
  'price-asc': { sortBy: 'price', sortDir: 'asc' },
  'price-desc': { sortBy: 'price', sortDir: 'desc' },
  newest: { sortBy: 'id', sortDir: 'desc' },
};

export function isSortKey(value: unknown): value is SortKey {
  return typeof value === 'string' && Object.prototype.hasOwnProperty.call(SORT_PARAMS, value);
}

export interface ProductQuery {
  q?: string;
  category?: string;
  /** Prices in the same currency as the products. Whole or decimal numbers, as text or as numbers. */
  minPrice?: number | string;
  maxPrice?: number | string;
  /** Without one the products come in the order they were added. */
  sort?: SortKey;
  /** Zero-based, as the API expects. */
  page: number;
  size: number;
}

/** Lists products, or searches when a search term, a category or a price limit is given. */
export function fetchProducts(query: ProductQuery, options?: RequestOptions): Promise<Page<Product>> {
  const q = query.q?.trim();
  const category = query.category?.trim();
  const sort = query.sort ? SORT_PARAMS[query.sort] : { sortBy: 'id', sortDir: 'asc' };
  const hasPrice = [query.minPrice, query.maxPrice].some((value) => value !== undefined && value !== '');
  if (q || category || hasPrice) {
    return getJson<Page<Product>>(
      withQuery('/api/products/search', {
        searchTerm: q,
        category,
        minPrice: query.minPrice,
        maxPrice: query.maxPrice,
        page: query.page,
        size: query.size,
        // Left out when nobody chose a sort, so the plain search keeps its default order.
        sortBy: query.sort ? sort.sortBy : undefined,
        sortDir: query.sort ? sort.sortDir : undefined,
      }),
      options,
    );
  }
  return getJson<Page<Product>>(
    withQuery('/api/products', { page: query.page, size: query.size, sortBy: sort.sortBy, sortDir: sort.sortDir }),
    options,
  );
}

export function fetchCategories(options?: RequestOptions): Promise<string[]> {
  return getJson<string[]>('/api/products/categories', options);
}

export function fetchProductBySku(sku: string, options?: RequestOptions): Promise<Product> {
  return getJson<Product>(`/api/products/sku/${encodeURIComponent(sku)}`, options);
}

/** One product with images, highlights, specifications and stock. A missing id answers 404. */
export function getProductDetails(id: number, options?: RequestOptions): Promise<ProductDetail> {
  return getJson<ProductDetail>(`/api/products/${encodeURIComponent(String(id))}/details`, options);
}

export async function fetchProductCount(options?: RequestOptions): Promise<number> {
  const page = await getJson<Page<Product>>(withQuery('/api/products', { size: 1 }), options);
  return page.totalElements;
}
