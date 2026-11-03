package jp.photoviewer.dbx.data;

import androidx.room.Database;
import androidx.room.RoomDatabase;

@Database(entities = {PhotoEntity.class}, version = 1, exportSchema = false)
public abstract class AppDatabase extends RoomDatabase {
    public abstract PhotoDao photos();
}
