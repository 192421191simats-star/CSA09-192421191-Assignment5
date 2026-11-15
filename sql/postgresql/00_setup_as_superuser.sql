-- Run once as the postgres superuser:  sudo -u postgres psql -f sql/postgresql/00_setup_as_superuser.sql
-- Creates a LOCAL TEST database used only for this assignment.
CREATE ROLE csa09 LOGIN PASSWORD 'csa09-test';
CREATE DATABASE csa09_a5 OWNER csa09;
