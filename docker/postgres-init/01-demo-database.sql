-- Runs once, when the Postgres volume is first created. The containerised stack uses its own
-- database (esm_demo) so its demo data never mixes with the "esm" database used for IDE
-- development against the same server.
CREATE DATABASE esm_demo;
