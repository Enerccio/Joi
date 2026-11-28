-- resources (Marginalia's Resource) becomes an ExtendableEntity, as Marginalia's V33: extensions and Joi keep their own data about
-- a file in its attributes (Joi: the size of a chat image, so her history's count of tokens reads no file). A platform entity
-- change, so Joi needs the columns too. extendedContent is gzipped by the entity when it is not small; _fulltext is not filled
-- (the files are not searched). The rows that exist have no content.
ALTER TABLE resources ADD COLUMN extendedContent blob;
ALTER TABLE resources ADD COLUMN _fulltext clob;
