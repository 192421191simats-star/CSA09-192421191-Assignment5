-- Run once as root:  mysql -u root -p < sql/mysql/00_setup_as_root.sql
CREATE DATABASE IF NOT EXISTS csa09_a5 CHARACTER SET utf8mb4;
CREATE USER IF NOT EXISTS 'csa09'@'localhost' IDENTIFIED BY 'csa09-test';
GRANT ALL PRIVILEGES ON csa09_a5.* TO 'csa09'@'localhost';
SET GLOBAL local_infile = 1;   -- only for the LOAD DATA alternative in TaskB3
