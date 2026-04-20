INSERT IGNORE INTO user (id, created_at, updated_at, email, username)
VALUES (1, '2023-01-01 00:00:00', '2023-01-01 00:00:00', 'ogu@platypus.com', 'ogu');

INSERT IGNORE INTO user (id, created_at, updated_at, email, username)
VALUES (2, '2023-01-01 00:00:00', '2023-01-01 00:00:00', 'dduzy@platypus.com', 'dduzy');

INSERT IGNORE INTO product (id, created_at, updated_at, description, name, price, stock, seller_id)
VALUES (1, '2023-01-01 00:00:00', '2023-01-01 00:00:00', 'Ogu Platypus', 'Ogu T', 50000, 59, 2);

INSERT IGNORE INTO product (id, created_at, updated_at, description, name, price, stock, seller_id)
VALUES (2, '2023-01-01 00:00:00', '2023-01-01 00:00:00', 'Ogu Platypus', 'Baby Ogu T', 30000, 59, 2);
