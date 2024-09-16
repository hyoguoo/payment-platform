INSERT INTO user (id, created_at, updated_at, email, username)
SELECT 1, '2023-01-01 00:00:00', '2023-01-01 00:00:00', 'ogu@platypus.com', 'ogu'
WHERE NOT EXISTS (SELECT * FROM user WHERE id = 1);

INSERT INTO user (id, created_at, updated_at, email, username)
SELECT 2, '2023-01-01 00:00:00', '2023-01-01 00:00:00', 'dduzy@platypus.com', 'dduzy'
WHERE NOT EXISTS (SELECT * FROM user WHERE id = 2);

INSERT INTO product (id, created_at, updated_at, description, name, price, stock, seller_id)
SELECT 1, '2023-01-01 00:00:00', '2023-01-01 00:00:00', 'Ogu Platypus', 'Ogu T', 50000, 59, 2
WHERE NOT EXISTS (SELECT * FROM product WHERE id = 1);
