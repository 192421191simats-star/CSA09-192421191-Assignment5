-- CSA09 Assignment 5 - test schema (MySQL 8.0.16+, CHECK constraints enforced)
-- mysql -u csa09 -p csa09_a5 < sql/mysql/01_schema.sql
USE csa09_a5;
SET FOREIGN_KEY_CHECKS = 0;
DROP TABLE IF EXISTS marks, course, student, wallet, users, bus_pass, bus_pass_staging;
SET FOREIGN_KEY_CHECKS = 1;

-- B3 / B4: bus passes
CREATE TABLE bus_pass (
  pass_id        VARCHAR(10)  PRIMARY KEY,
  reg_no         CHAR(9)      NOT NULL,
  name           VARCHAR(60)  NOT NULL,
  route_no       INT          NOT NULL CHECK (route_no BETWEEN 1 AND 99),
  amount         DECIMAL(8,2) NOT NULL CHECK (amount >= 0),
  boarding_stop  VARCHAR(40),
  valid_until    DATE,
  campus         SMALLINT     NOT NULL DEFAULT 1
);
CREATE INDEX idx_bus_pass_route ON bus_pass(route_no);
CREATE INDEX idx_bus_pass_reg   ON bus_pass(reg_no);

-- B2: login check (plain-text passwords ONLY because this is a throw-away test database)
CREATE TABLE users (
  id         VARCHAR(20) PRIMARY KEY,
  pwd        VARCHAR(40) NOT NULL,
  full_name  VARCHAR(60) NOT NULL,
  role       VARCHAR(10) NOT NULL
);
INSERT INTO users VALUES ('admin',   'Adm!n#2026', 'Transport Admin', 'ADMIN');
INSERT INTO users VALUES ('counter1','Cntr1-pass', 'Counter Clerk 1', 'CLERK');
INSERT INTO users VALUES ('arun',    'arun@123',   'Arun Kumar',      'STUDENT');

-- A5: wallets (balance can never go negative)
CREATE TABLE wallet (
  wallet_id  VARCHAR(20)  PRIMARY KEY,
  reg_no     CHAR(9)      NOT NULL,
  kind       VARCHAR(10)  NOT NULL,
  balance    DECIMAL(10,2) NOT NULL CHECK (balance >= 0)
);

-- A3: student / course / marks
CREATE TABLE student (
  reg_no   CHAR(9)     PRIMARY KEY,
  name     VARCHAR(60) NOT NULL,
  dept     VARCHAR(10) NOT NULL,
  year     SMALLINT    NOT NULL CHECK (year BETWEEN 1 AND 4),
  email    VARCHAR(80)
);
CREATE TABLE course (
  course_code VARCHAR(8)  PRIMARY KEY,
  title       VARCHAR(80) NOT NULL,
  credits     SMALLINT    NOT NULL
);
CREATE TABLE marks (
  reg_no      CHAR(9)    NOT NULL REFERENCES student(reg_no),
  course_code VARCHAR(8) NOT NULL REFERENCES course(course_code),
  mark        SMALLINT   NOT NULL CHECK (mark BETWEEN 0 AND 100),
  PRIMARY KEY (reg_no, course_code)
);
