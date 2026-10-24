ALTER TABLE UserTasks ADD COLUMN important INTEGER CHECK(important IN (0, 1));
ALTER TABLE UserTasks ADD COLUMN urgent INTEGER CHECK(urgent IN (0, 1));
