/**
 * Utilities for working with generated Alpaca REST clients.
 *
 * <p>This package contains adapters for generated callback-style async methods, wrappers for HTTP
 * response metadata, pagination helpers for endpoints that return {@code next_page_token}, and
 * rate-limit header parsing. The helpers keep generated Broker, Market Data, and Trading API types
 * separate so callers can preserve the checked exception type for the API they are using.
 */
package markets.alpaca.client.rest;
