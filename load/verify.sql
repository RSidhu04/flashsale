-- psql -v product_id=THE_ID -f load/verify.sql
SELECT p.id, i.available, count(o.id) AS orders_created,
       COALESCE(sum(o.quantity),0) AS units_sold,
       i.available + COALESCE(sum(o.quantity),0) AS accounted_initial_stock
FROM products p JOIN inventory i ON i.product_id=p.id
LEFT JOIN orders o ON o.product_id=p.id
WHERE p.id = :product_id
GROUP BY p.id, i.available;
