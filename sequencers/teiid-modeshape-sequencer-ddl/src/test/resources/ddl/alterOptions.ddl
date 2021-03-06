CREATE FOREIGN TABLE myTable
(
 t1 integer,
 t2 varchar,
 PRIMARY KEY(t1, t2)
);

ALTER FOREIGN TABLE myTable OPTIONS(ADD CARDINALITY 12);
ALTER FOREIGN TABLE myTable OPTIONS(ADD FOO 'BAR');

ALTER FOREIGN TABLE myTable OPTIONS(SET CARDINALITY 24);
ALTER FOREIGN TABLE myTable OPTIONS(SET FOO 'BARBAR');

ALTER FOREIGN TABLE myTable OPTIONS(DROP CARDINALITY);
ALTER FOREIGN TABLE myTable OPTIONS(DROP FOO);

ALTER FOREIGN TABLE myTable ALTER COLUMN t1 OPTIONS(ADD NULL_VALUE_COUNT 12);
ALTER FOREIGN TABLE myTable ALTER COLUMN t2 OPTIONS(ADD FOO 'BAR');

ALTER FOREIGN TABLE myTable ALTER COLUMN t1 OPTIONS(DROP NULL_VALUE_COUNT);
ALTER FOREIGN TABLE myTable ALTER COLUMN t2 OPTIONS(DROP FOO);

CREATE VIRTUAL VIEW myView
(
 v1 integer,
 v2 varchar
)
OPTIONS (CARDINALITY 12)
AS
 select e1, e2 from foo.bar;

ALTER VIRTUAL VIEW myView OPTIONS(ADD CARDINALITY 12);
ALTER VIRTUAL VIEW myView OPTIONS(ADD FOO 'BAR');

ALTER VIRTUAL VIEW myView OPTIONS(SET CARDINALITY 24);
ALTER VIRTUAL VIEW myView OPTIONS(SET FOO 'BARBAR');

ALTER VIRTUAL VIEW myView OPTIONS(DROP CARDINALITY);
ALTER VIRTUAL VIEW myView OPTIONS(DROP FOO);

ALTER VIRTUAL VIEW myView ALTER COLUMN v1 OPTIONS(ADD NULL_VALUE_COUNT 12);
ALTER VIRTUAL VIEW myView ALTER COLUMN v2 OPTIONS(ADD FOO 'BAR');

ALTER VIRTUAL VIEW myView ALTER COLUMN v1 OPTIONS(DROP NULL_VALUE_COUNT);
ALTER VIRTUAL VIEW myView ALTER COLUMN v2 OPTIONS(DROP FOO);

CREATE FOREIGN PROCEDURE myProc(
 OUT p1 boolean,
 p2 varchar,
 INOUT p3 decimal
);

ALTER FOREIGN PROCEDURE myProc OPTIONS(SET NAMEINSOURCE 'x')
ALTER FOREIGN PROCEDURE myProc ALTER PARAMETER p2 OPTIONS (ADD x 'y', SET a 'b');
ALTER FOREIGN PROCEDURE myProc OPTIONS(DROP UPDATECOUNT, DROP NAMEINSOURCE);
