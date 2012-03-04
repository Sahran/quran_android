package com.quran.labs.androidquran.data;

import java.util.List;

import android.app.SearchManager;
import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.provider.BaseColumns;
import android.util.Log;

import com.quran.labs.androidquran.R;
import com.quran.labs.androidquran.common.TranslationItem;
import com.quran.labs.androidquran.common.TranslationsDBAdapter;
import com.quran.labs.androidquran.util.QuranFileUtils;
import com.quran.labs.androidquran.util.QuranUtils;

public class QuranDataProvider extends ContentProvider {

	public static String AUTHORITY =
		"com.quran.labs.androidquran.data.QuranDataProvider";
	public static final Uri
		SEARCH_URI = Uri.parse("content://" + AUTHORITY +  "/quran/search");

	 public static final String VERSES_MIME_TYPE = 
		 ContentResolver.CURSOR_DIR_BASE_TYPE +
		 "/vnd.com.quran.labs.androidquran";
	 public static final String AYAH_MIME_TYPE =
		 ContentResolver.CURSOR_ITEM_BASE_TYPE +
          "/vnd.com.quran.labs.androidquran";
	 public static final String QURAN_ARABIC_DATABASE = "quran.ar.db";

	// UriMatcher stuff
	private static final int SEARCH_VERSES = 0;
	private static final int GET_VERSE = 1;
	private static final int SEARCH_SUGGEST = 2;
	private static final UriMatcher sURIMatcher = buildUriMatcher();

	private DatabaseHandler database = null;
	
	private static UriMatcher buildUriMatcher() {
		UriMatcher matcher =  new UriMatcher(UriMatcher.NO_MATCH);
		matcher.addURI(AUTHORITY, "quran/search", SEARCH_VERSES);
		matcher.addURI(AUTHORITY, "quran/search/*", SEARCH_VERSES);
		matcher.addURI(AUTHORITY, "quran/search/*/*", SEARCH_VERSES);
		matcher.addURI(AUTHORITY, "quran/verse/#/#", GET_VERSE);
		matcher.addURI(AUTHORITY, "quran/verse/*/#/#", GET_VERSE);
		matcher.addURI(AUTHORITY, SearchManager.SUGGEST_URI_PATH_QUERY,
				SEARCH_SUGGEST);
		matcher.addURI(AUTHORITY, SearchManager.SUGGEST_URI_PATH_QUERY + "/*", 
				SEARCH_SUGGEST);
		return matcher;
	}

	@Override
	public boolean onCreate() {
		return true;
	}

	@Override
	public Cursor query(Uri uri, String[] projection, String selection,
			String[] selectionArgs, String sortOrder) {
		switch (sURIMatcher.match(uri)) {
		case SEARCH_SUGGEST:
			if (selectionArgs == null) {
				throw new IllegalArgumentException(
						"selectionArgs must be provided for the Uri: " + uri);
			}

			return getSuggestions(selectionArgs[0]);
		case SEARCH_VERSES:
			if (selectionArgs == null) {
				throw new IllegalArgumentException(
						"selectionArgs must be provided for the Uri: " + uri);
			}

			if (selectionArgs.length == 1)
				return search(selectionArgs[0]);
			else return search(selectionArgs[0], selectionArgs[1], true);
		case GET_VERSE:
			return getVerse(uri);
		default:
			throw new IllegalArgumentException("Unknown Uri: " + uri);
		}
	}

	private Cursor search(String query){
		if (QuranUtils.doesStringContainArabic(query) &&
				QuranFileUtils.hasTranslation(QURAN_ARABIC_DATABASE)){
			Cursor c = search(query, QURAN_ARABIC_DATABASE, true);
			if (c != null) return c;
		}
		
		TranslationItem active = getActiveTranslation();
		if (active == null) return null;
		return search(query, active.getFileName(), true);
	}

	private TranslationItem getActiveTranslation(){
		// i used to cache this, but i stopped because if you set a new active
		// translation or remove a translation, it won't get reflected here.
		TranslationsDBAdapter dba = new TranslationsDBAdapter(getContext());
		TranslationItem activeTranslation = dba.getActiveTranslation();
		dba.close();
		return activeTranslation;
	}
	
	private Cursor getSuggestions(String query){
		if (query.length() < 3) return null;
		
		int numItems = 1;
		if (QuranUtils.doesStringContainArabic(query) &&
				QuranFileUtils.hasTranslation(QURAN_ARABIC_DATABASE)){
			numItems = 2;
		}
		
		//TranslationItem[] items = dba.getAvailableTranslations(true);
		TranslationItem[] items = new TranslationItem[numItems];
		if (numItems == 1) items[0] = getActiveTranslation();
		else {
			TranslationItem arabicItem = new TranslationItem();
			arabicItem.setFileName(QURAN_ARABIC_DATABASE);
			items[0] = arabicItem;
			items[1] = getActiveTranslation();
		}
				
		String[] cols = new String[]{ BaseColumns._ID,
				SearchManager.SUGGEST_COLUMN_TEXT_1,
				SearchManager.SUGGEST_COLUMN_TEXT_2,
				SearchManager.SUGGEST_COLUMN_INTENT_DATA_ID };
		MatrixCursor mc = new MatrixCursor(cols);
		
		boolean gotResults = false;
		for (TranslationItem item : items) {
			if ((item == null) || (gotResults)) continue;
			Cursor suggestions = search(query, item.getFileName(), false);
			
			if (suggestions.moveToFirst()){
				do {
					int sura = suggestions.getInt(0);
					int ayah = suggestions.getInt(1);
					String text = suggestions.getString(2);
					String foundText = getContext().getString(R.string.found_in_sura) + " " +
						QuranInfo.getSuraName(sura-1) + ", " + 
						getContext().getString(R.string.quran_ayah) + " " + ayah;
					
					gotResults = true;
					MatrixCursor.RowBuilder row = mc.newRow();
					int id = 0;
					for (int j=1; j<sura;j++){
						id += QuranInfo.getNumAyahs(j);
					}
					id += ayah;
					
					row.add(id);
					row.add(text);
					row.add(foundText);
					row.add(id);
				} while (suggestions.moveToNext());
			}
			suggestions.close();
			database.closeDatabase();
			database = null;
		}
		
		return mc;
	}

	private Cursor search(String query, String language, boolean wantSnippets) {
		Log.d("qdp", "q: " + query + ", l: " + language);
		if (language == null) return null;
		
		if (database == null)
			database = new DatabaseHandler(language);
		return database.search(query, wantSnippets);
	}

	private Cursor getVerse(Uri uri){
		int sura = 1;
		int ayah = 1;
		TranslationItem langType = getActiveTranslation();
		String lang = (langType != null)? langType.getFileName() : null;
		if (lang == null) return null;

		List<String> parts = uri.getPathSegments();
		for (String s : parts)
			Log.d("qdp", "uri part: " + s);

		if (database == null)
			database = new DatabaseHandler(lang);
		return database.getVerse(sura, ayah);
	}

	@Override
	public String getType(Uri uri) {
		switch (sURIMatcher.match(uri)) {
		case SEARCH_VERSES:
			return VERSES_MIME_TYPE;
		case GET_VERSE:
			return AYAH_MIME_TYPE;
		case SEARCH_SUGGEST:
			return SearchManager.SUGGEST_MIME_TYPE;
		default:
			throw new IllegalArgumentException("Unknown URL " + uri);
		}
	}

	@Override
	public Uri insert(Uri uri, ContentValues values) {
		throw new UnsupportedOperationException();
	}

	@Override
	public int update(Uri uri, ContentValues values, String selection,
			String[] selectionArgs) {
		throw new UnsupportedOperationException();
	}

	@Override
	public int delete(Uri uri, String selection, String[] selectionArgs) {
		throw new UnsupportedOperationException();
	}
}
