package com.rho.sync;

import j2me.util.ArrayList;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.HttpURLConnection;

import com.rho.IHttpConnection;
import com.rho.db.PerstLiteAdapter;
import com.rho.util.URI;
import com.xruby.runtime.builtin.ObjectFactory;
import com.xruby.runtime.builtin.RubyArray;
import com.xruby.runtime.builtin.RubyHash;
import com.xruby.runtime.builtin.RubyInteger;
import com.xruby.runtime.builtin.RubyString;
import com.xruby.runtime.lang.RubyConstant;
import com.xruby.runtime.lang.RubyValue;

/**
 * The Class SyncUtil.
 */
public class SyncUtil {

	/** The adapter. */
	public static PerstLiteAdapter adapter = null;
    public static byte[]  m_byteBuffer = new byte[4096];

    public static void init(){
		SyncUtil.adapter = PerstLiteAdapter.alloc(null);
		SyncBlob.DBCallback callback = new SyncBlob.DBCallback();
		SyncUtil.adapter.setDbCallback(callback);
    }
	/**
	 * Creates the array.
	 * 
	 * @return the ruby array
	 */
	public static RubyArray createArray() {
		return new RubyArray();
	}

	/**
	 * Creates the hash.
	 * 
	 * @return the ruby hash
	 */
	public static RubyHash createHash() {
		return ObjectFactory.createHash();
	}

	/**
	 * Creates the integer.
	 * 
	 * @param val
	 *            the val
	 * 
	 * @return the ruby integer
	 */
	public static RubyInteger createInteger(long val) {
		return ObjectFactory.createInteger(val);
	}

	/**
	 * Creates the string.
	 * 
	 * @param val
	 *            the val
	 * 
	 * @return the ruby string
	 */
	public static RubyString createString(String val) {
		return ObjectFactory.createString(val);
	}

	/**
	 * Fetch the changes for a given source
	 * 
	 * @param source
	 * @param client_id
	 * @param params
	 * @return
	 */
	public static SyncFetchResult fetchRemoteChanges(SyncSource source, String client_id, String params) {
		int success=0, deleted=0, inserted=0;
		long start=0, duration=0;
		String data = null;
		SyncJSONParser.SyncHeader header = new SyncJSONParser.SyncHeader();
		int nTry = 0, nTotal = -1;
		boolean repeat = true;

			start = System.currentTimeMillis();
			String session = get_session(source);
	    
		do{
			String fetch_url = source.get_sourceUrl() +
					((params != null && params.length() > 0) ? SyncConstants.SYNC_ASK_ACTION : "") +
					SyncConstants.SYNC_FORMAT +
					"&client_id=" + client_id
					+ "&p_size=" + SyncConstants.SYNC_PAGE_SIZE;
			if (params != null && params.length() > 0) {
				fetch_url += "&question=" + params;
				// Don't repeat if we're calling ask method
				repeat = false;
			}
		    if ( header._token.length() > 0 )
		    	fetch_url += "&ack_token=" + header._token;
			
			if ( source.get_token().length() == 0 || source.get_token().equals("0") )
				processToken("1", source );

			header = new SyncJSONParser.SyncHeader();
			
			try {
				data = SyncManager.fetchRemoteData( fetch_url, session, true);
		} catch (IOException e) {
			System.out
					.println("There was an error fetching data from the sync source: "
							+ e.getMessage());
		}
		if (data != null) {
				ArrayList list = SyncJSONParser.parseObjectValues(data, header);
				
				processToken(header._token, source);
				
				int count = list.size();
				if ( nTotal < 0 )
					nTotal = 0;
				nTotal += count; 
			if (count > 0) {
					
				for (int i = 0; i < count; i++) {
						SyncObject syncObj = ((SyncObject) list.get(i)); 
						String dbOp = syncObj.getDbOperation();
						if (dbOp != null) {
							
							if (dbOp.equalsIgnoreCase("insert")) {
//								SyncBlob.insertOp(syncObj, client_id, SyncBlob.SYNC_STAGE);
								
								syncObj.insertIntoDatabase();
				    	inserted++;
							} else if (dbOp.equalsIgnoreCase("delete")) {
								syncObj.deleteFromDatabase();
				    	deleted++;
				    }
				  }
				}
			}
			success = 1;
			}else{
                nTry++;
		}
		} while (header._count != 0 && nTry < SyncConstants.MAX_SYNC_TRY_COUNT && repeat);
		
		duration = (System.currentTimeMillis() - start) / 1000L;
		updateSourceSyncStatus(source, inserted, deleted, duration, success);
		
		return new SyncFetchResult(nTotal,header._count == -1);
	}

	private static void processToken( String token, SyncSource source ){
		if ( token.length() > 0 && !token.equals("0") && !token.equals("1") &&  
			 source.get_token().equals(token)) {
			//Delete non-confirmed records

			RubyHash where = createHash();
			where.add(createString("source_id"), createInteger(source
					.get_sourceId()));
			where.add(PerstLiteAdapter.TOKEN, createString(token));
			
			adapter.deleteFromTable(createString("object_values"), where);
		} else //if (token.length() > 0) 
		{
			source.set_token(token);
			
			RubyHash values = SyncUtil.createHash();
			values.add(PerstLiteAdapter.TOKEN, createString(token));
			RubyHash where = SyncUtil.createHash();
			where.add(PerstLiteAdapter.SOURCE_ID, createInteger(source
					.get_sourceId()));
			adapter.updateIntoTable(createString(SyncConstants.SOURCES_TABLE),
					values, where);
		}
	}
	
	/**
	 * Update the sync source status after each sync run
	 * 
	 * @param source
	 * @param inserted
	 * @param deleted
	 * @param duration
	 * @param success
	 */
	private static void updateSourceSyncStatus(SyncSource source, int inserted,
			int deleted, long duration, int success) {
		RubyHash values = SyncUtil.createHash();
		long now = System.currentTimeMillis() / 1000;
		values.add(PerstLiteAdapter.Table_sources.LAST_UPDATED,
				createInteger(now));
		values.add(PerstLiteAdapter.Table_sources.LAST_INSERTED_SIZE,
				createInteger(inserted));
		values.add(PerstLiteAdapter.Table_sources.LAST_DELETED_SIZE,
				createInteger(deleted));
		values.add(PerstLiteAdapter.Table_sources.LAST_SYNC_DURATION,
				createInteger(duration));
		values.add(PerstLiteAdapter.Table_sources.LAST_SYNC_SUCCESS,
				createInteger(success));
		RubyHash where = SyncUtil.createHash();
		where.add(PerstLiteAdapter.SOURCE_ID, createInteger(source
				.get_sourceId()));
		adapter.updateIntoTable(createString(SyncConstants.SOURCES_TABLE),
				values, where);
	}

	/**
	 * Gets the object value list.
	 * 
	 * @param id
	 *            the id
	 * 
	 * @return the object value list
	 */
	public static RubyArray getObjectValueList(int id) {
		RubyArray arr = createArray();
		RubyHash where = createHash();
		arr.add(createString("object_values"));
		arr.add(createString("*"));
		where.add(createString("source_id"), createInteger(id));
		arr.add(where);
		return (RubyArray) adapter.selectFromTable(arr);
	}

	/**
	 * Gets the op list from database.
	 * 
	 * @param type
	 *            the type
	 * @param source
	 *            the source
	 * 
	 * @return the op list from database
	 */
	public static ArrayList getOpListFromDatabase(String type, SyncSource source) {
		System.out.println("Checking database for " + type + " operations...");
		RubyArray arr = createArray();
		RubyHash where = createHash();
		String operation = null;
		arr.add(createString("object_values"));
		arr.add(createString("*"));
		where.add(createString("update_type"), createString(type));
		where.add(createString("source_id"), createInteger(source
				.get_sourceId()));
		arr.add(where);
		RubyArray rows = (RubyArray) adapter.selectFromTable(arr);
		ArrayList objects = getSyncObjectList(rows);
		System.out.println("Found " + objects.size() + " records for " + type
				+ " processing...");
		ArrayList results = new ArrayList();

		if (type != null) {
			if (type.equalsIgnoreCase("create")) {
				operation = SyncConstants.UPDATE_TYPE_CREATE;
			} else if (type.equalsIgnoreCase("update")) {
				operation = SyncConstants.UPDATE_TYPE_UPDATE;
			} else if (type.equalsIgnoreCase("delete")) {
				operation = SyncConstants.UPDATE_TYPE_DELETE;
			}
		}

		for (int i = 0; i < objects.size(); i++) {
			SyncObject current = (SyncObject) objects.get(i);
			SyncOperation newOperation = new SyncOperation(operation, current);
			results.add(newOperation);
			System.out
					.println("Adding sync operation (attrib, source_id, object, value, update_type, uri): "
							+ current.getAttrib()
							+ ", "
							+ current.getSourceId()
							+ ", "
							+ current.getObject()
							+ ", "
							+ (current.getValue() == null ? "null" : current
									.getValue())
							+ ", "
							+ operation
							+ ", "
							+ source.get_sourceUrl());
		}
		return results;
	}

	public static void removeOpListFromDatabase(String type, SyncSource source) {
		RubyHash where = createHash();
		where.add(createString("update_type"), createString(type));
		where.add(createString("source_id"), createInteger(source
				.get_sourceId()));
		adapter.deleteFromTable(createString("object_values"), where);
	}

	/**
	 * Returns the parameter string for a source
	 * 
	 * @param sourceId
	 * @return
	 */
	public static String getParamsForSource(SyncSource source) {
		String askType = "ask";
		RubyHash where = createHash();
		RubyArray arr = createArray();
		arr.add(createString("object_values"));
		arr.add(createString("*"));
		where.add(createString("source_id"), createInteger(source.get_sourceId()));
		where.add(createString("update_type"), createString(askType));
		arr.add(where);
		RubyArray list = (RubyArray) adapter.selectFromTable(arr);
		if ( list.size() == 0 )
			return "";
		
		// There should only be one result
		RubyHash element = (RubyHash) list.at(createInteger(0));
		String params = element.get(createString("value")).asString();
		removeOpListFromDatabase(askType, source);
		return params;
	}

	/**
	 * Gets the source list.
	 * 
	 * @return the source list
	 */
	public static RubyArray getSourceList() {
		RubyArray arr = createArray();
		if ( adapter == null )
			return arr;
		
		arr.add(createString("sources"));
		arr.add(createString("*"));
		
		RubyHash order = createHash();
		order.add(createString("order by"), createString("source_id"));
		arr.add(RubyConstant.QNIL); // where
	    arr.add(order);
	    
		return (RubyArray) adapter.selectFromTable(arr);
	}

	/**
	 * Gets the sync object list.
	 * 
	 * @param list
	 *            the list
	 * 
	 * @return the sync object list
	 */
	public static ArrayList getSyncObjectList(RubyArray list) {
		ArrayList results = new ArrayList();
		for (int i = 0; i < list.size(); i++) {
			RubyHash element = (RubyHash) list.at(createInteger(i));
			String attrib = element.get(createString("attrib")).asString();
			RubyValue val = element.get(createString("value"));
			String value = val == null ? null : val.asString();
			String object = element.get(createString("object")).asString();
			String updateType = element.get(createString("update_type"))
					.asString();
			
			String type = "";
			val = element.get(createString("attrib_type"));
			if ( val != null && val != RubyConstant.QNIL)
				type = val.asString();
			
			int sourceId = element.get(createString("source_id")).toInt();
			results.add(new SyncObject(attrib, sourceId, object, value,
					updateType, type));
		}
		return results;
	}

	/**
	 * Prints the results.
	 * 
	 * @param objects
	 *            the objects
	 */
	public static void printResults(RubyArray objects) {
		// Debug code to print results
		for (int j = 0; j < objects.size(); j++) {
			RubyHash objectElement = (RubyHash) objects.at(SyncUtil
					.createInteger(j));
			String value = objectElement.get(SyncUtil.createString("value"))
					.toString();
			String attrib = objectElement.get(SyncUtil.createString("attrib"))
					.toString();
			System.out.println("value[" + j + "][" + attrib + "]: " + value);
		}
	}

	private static int get_start_source( RubyArray sources )
	{
		for (int i = 0; i < sources.size(); i++) {
			RubyHash element = (RubyHash) sources.at(SyncUtil.createInteger(i));
			RubyValue token = element.get(PerstLiteAdapter.TOKEN);
			if (token != null && token != RubyConstant.QNIL)
			{
				String strToken = token.toStr();
				if ( strToken.length() > 0 && !strToken.equals("0") )
					return i;
			}
		}
		
		return 0;
	}
	
	static class SyncFetchResult {
		int available = 0;
		boolean stopSync = false;
		
		SyncFetchResult() {}
		SyncFetchResult( int avail, boolean bStop ) {
			available = avail;
			stopSync = bStop;
		}
	};
	
	/**
	 * Process local changes.
	 * 
	 * @return the int
	 */
	public static int processLocalChanges(SyncThread thread) {
		RubyArray sources = SyncUtil.getSourceList();

		String client_id = null;
		int nStartSrc = get_start_source(sources);
		SyncFetchResult syncResult = new SyncFetchResult();
		
		for (int i = nStartSrc; i < sources.size() && !thread.isStop() && !syncResult.stopSync; i++) {
			RubyHash element = (RubyHash) sources.at(SyncUtil.createInteger(i));
			String url = element.get(PerstLiteAdapter.SOURCE_URL).toString();
			int id = element.get(PerstLiteAdapter.SOURCE_ID).toInt();
			RubyValue token = element.get(PerstLiteAdapter.TOKEN);
			SyncSource current = new SyncSource(url, id);
			if ( token != null && token != RubyConstant.QNIL )
				current.set_token(token.toStr());
			
			if ( client_id == null )
				client_id = get_client_id(current);
			
			if (thread.isStop())
				break;
			
			System.out.println("URL: " + current.get_sourceUrl());
			int success = 0;
			success += processOpList(current, "create", client_id);
			if (thread.isStop())
				break;
			success += processOpList(current, "update", client_id);
			if (thread.isStop())
				break;
			success += processOpList(current, "delete", client_id);
			if (thread.isStop())
				break;
			
			if (success > 0) {
				System.out
						.println("Remote update failed, not continuing with sync...");
			} else {
				String askParams = SyncUtil.getParamsForSource(current);
				syncResult = SyncUtil.fetchRemoteChanges(current, client_id, askParams);
				System.out.println("Successfully processed " + syncResult.available
						+ " records...");
				if (SyncConstants.DEBUG) {
					RubyArray objects = SyncUtil.getObjectValueList(current
							.get_sourceId());
					SyncUtil.printResults(objects);
				}
				if ( !thread.isStop() )
					SyncEngine.getNotificationImpl().fireNotification(id, syncResult.available);
			}
		}
		return SyncConstants.SYNC_PROCESS_CHANGES_OK;
	}

	/**
	 * Process op list.
	 * 
	 * @param source
	 *            the source
	 * @param type
	 *            the type
	 * 
	 * @return the int
	 */
	private static int processOpList(SyncSource source, String type,
			String clientId) {
		int success = SyncConstants.SYNC_PUSH_CHANGES_OK;
		ArrayList list = getOpListFromDatabase(type, source);
		if (list.size() == 0) {
			return success;
		}
		System.out.println("Found " + list.size()
				+ " available records for processing...");
		
		ArrayList listBlobs = SyncBlob.extractBlobs(list);
		
		if (pushRemoteChanges(source, list, clientId) != SyncConstants.SYNC_PUSH_CHANGES_OK) {
			success = SyncConstants.SYNC_PUSH_CHANGES_ERROR;
		} else {
			if ( SyncBlob.pushRemoteBlobs(source, listBlobs, clientId) == SyncConstants.SYNC_PUSH_CHANGES_OK )
			{
			// We're done processsing, remove from database so we
			// don't process again
			removeOpListFromDatabase(type, source);
		}
			else
				success = SyncConstants.SYNC_PUSH_CHANGES_ERROR;
		}
		return success;
	}

	/**
	 * Push remote changes.
	 * 
	 * @param source
	 *            the source
	 * @param list
	 *            the list
	 * 
	 * @return the int
	 */
	public static int pushRemoteChanges(SyncSource source, ArrayList list,
			String clientId) {
		int success = 0;
		StringBuffer data = new StringBuffer();
		String url = null;
		if (list.size() == 0) {
			return SyncConstants.SYNC_PUSH_CHANGES_OK;
		}

		for (int i = 0; i < list.size(); i++) {
			data.append(((SyncOperation) list.get(i)).get_postBody());
			if (i != (list.size() - 1)) {
				data.append("&");
			}
		}
		ByteArrayInputStream dataStream = null;
		try {
			// Construct the post url
			url = source.get_sourceUrl() + "/"
					+ ((SyncOperation) list.get(0)).get_operation()
					+ "?client_id=" + clientId;
			String session = get_session(source);
			
			dataStream = new ByteArrayInputStream(data.toString().getBytes()); 
			success = SyncManager.pushRemoteData(url, dataStream, session,true,
					"application/x-www-form-urlencoded");
		} catch (IOException e) {
			System.out.println("There was an error pushing changes: "
					+ e.getMessage());
			success = SyncConstants.SYNC_PUSH_CHANGES_ERROR;
		}

		if ( dataStream != null ){
			try{dataStream.close();}catch(IOException exc){}
			dataStream = null;
		}
		
		return success == SyncConstants.SYNC_PUSH_CHANGES_OK ? SyncConstants.SYNC_PUSH_CHANGES_OK
				: SyncConstants.SYNC_PUSH_CHANGES_ERROR;
	}

	/**
	 * 
	 * @return size of objectValues table
	 */
	public static int getObjectCountFromDatabase( String dbName ) {
		RubyArray arr = createArray();
		arr.add(createString(dbName));// "object_values")); //table name
		arr.add(createString("*")); // attributes
		// arr.add(createString("source_id")); //not nil attributes
		arr.add(RubyConstant.QNIL); // where

		RubyHash params = createHash();
		params.add(createString("count"), RubyConstant.QTRUE);
		arr.add(params);
		
		RubyInteger results = (RubyInteger)adapter.selectFromTable(arr);
		return results == null ? 0 : results.toInt();
	}
	
	public static String get_client_id(SyncSource source) {
		String client_id = get_client_db_info("client_id");
		if ( client_id.length() == 0 ){
			String data = null;
			try {
				data = SyncManager.fetchRemoteData(source.get_sourceUrl()
						+ "/clientcreate" + SyncConstants.SYNC_FORMAT, "",
						false);
				
				if (data != null)
					client_id = SyncJSONParser.parseClientID(data);

				RubyHash hash = SyncUtil.createHash();
				hash.add(SyncUtil.createString("client_id"),
						createString(client_id));
				
				if ( getObjectCountFromDatabase(SyncConstants.CLIENT_INFO) > 0 )
					adapter.updateIntoTable(
							createString(SyncConstants.CLIENT_INFO), hash,
							RubyConstant.QNIL);
				else
					adapter.insertIntoTable(
							createString(SyncConstants.CLIENT_INFO), hash);
			} catch (IOException e) {
				System.out
						.println("There was an error fetching data from the sync source: "
								+ e.getMessage());
			}
		}
		return client_id;
	}

	public static String get_session(SyncSource source) {
		RubyArray arr = createArray();
		arr.add(createString("sources"));
		arr.add(PerstLiteAdapter.SESSION);
		
		RubyHash where = SyncUtil.createHash();
		where.add(PerstLiteAdapter.SOURCE_ID, createInteger(source
				.get_sourceId()));
		arr.add(where);
		RubyArray res = (RubyArray) adapter.selectFromTable(arr);
		if ( res.size() == 0 )
			return "";
		
		RubyHash element = (RubyHash) res.at(SyncUtil.createInteger(0));
		
		return element.get(PerstLiteAdapter.SESSION).toString();
	}
	
	private static String getSessionByDomain(String url){
		RubyArray sources = getSourceList();

		try {
		URI uri = new URI(url);
			for (int i = 0; i < sources.size(); i++) {
				try{
					RubyHash element = (RubyHash) sources.at(SyncUtil
							.createInteger(i));
					String sourceUrl = element.get(PerstLiteAdapter.SOURCE_URL)
							.toString();
					String session = element.get(PerstLiteAdapter.SESSION)
							.toString();
					if ( sourceUrl == null || sourceUrl.length() == 0 )
						continue;
					
			URI uriSrc = new URI(sourceUrl);
					if (session != null && session.length() > 0
							&& uri.getHost().equalsIgnoreCase(uriSrc.getHost()))
				return session;
				} catch (URI.MalformedURIException exc) {
				}
			}
		} catch (URI.MalformedURIException exc) {
		}
		
		return "";
	}
	
	static class ParsedCookie {
		String strAuth;
		String strSession;
	};

	/*
	 * private static void cutCookieField(ParsedCookie cookie, String strField){
	 * int nExp = cookie.strCookie.indexOf(strField); cookie.strFieldValue = "";
	 * if ( nExp > 0 ){ int nExpEnd = cookie.strCookie.indexOf(';', nExp); if (
	 * nExpEnd > 0 ){ cookie.strFieldValue =
	 * cookie.strCookie.substring(nExp+strField.length(), nExpEnd);
	 * cookie.strCookie = cookie.strCookie.substring(0, nExp) +
	 * cookie.strCookie.substring(nExpEnd+1); }else{ cookie.strFieldValue =
	 * cookie.strCookie.substring(nExp+strField.length()); cookie.strCookie =
	 * cookie.strCookie.substring(0, nExp); } } }
	 */
	
	private static void parseCookie( String value, ParsedCookie cookie ){
		boolean bAuth = false;
		boolean bSession = false;
		Tokenizer stringtokenizer = new Tokenizer(value, ";");
		while (stringtokenizer.hasMoreTokens()) {
			String tok = stringtokenizer.nextToken();
			tok = tok.trim();
			if (tok.length()==0) {
				continue;
			}
			int i = tok.indexOf('=');
			String s1;
			String s2;
			if (i > 0) {
				s1 = tok.substring(0, i);
				s2 = tok.substring(i + 1);
			} else {
				s1 = tok;
				s2 = "";
			}
			s1 = s1.trim();
			s2 = s2.trim();
			
			if ( s1.equalsIgnoreCase("auth_token") && s2.length() > 0 ){
				cookie.strAuth = s1 + "=" + s2;
				bAuth = true;
			}else if ( s1.equalsIgnoreCase("path") && s2.length() > 0 ){
				if ( bAuth )
					cookie.strAuth += ";" + s1 + "=" + s2;
				else if (bSession)
					cookie.strSession += ";" + s1 + "=" + s2;
			} else if (s1.equalsIgnoreCase("rhosync_session")
					&& s2.length() > 0) {
				cookie.strSession = s1 + "=" + s2;
				bSession = true;
			}

		}
	}
	
	private static String extractToc(String toc_name, String data) {
		int start = data.indexOf(toc_name);
		if (start!=-1) {
			int end = data.indexOf(';', start);
			if (end!=-1) {
				return data.substring(start, end);
			}
		}		
		return null;
	}
	
	private static ParsedCookie makeCookie(HttpURLConnection connection)
			throws IOException {
		ParsedCookie cookie = new ParsedCookie();
		
		for ( int i = 0; ; i++ ){
			String strField = connection.getHeaderFieldKey(i);
			if ( strField == null && i > 0 )
				break;
			
			if ( strField != null && strField.equalsIgnoreCase("Set-Cookie")) {
				String header_field = connection.getHeaderField(i);
				System.out.println("Set-Cookie: " + header_field);
				parseCookie( header_field, cookie );
				// Hack to make it work on 4.6 device which doesn't parse
				// cookies correctly
// if (cookie.strAuth==null) {
// String auth = extractToc("auth_token", header_field);
// cookie.strAuth = auth;
// System.out.println("Extracted auth_token: " + auth);
// }
				if (cookie.strSession==null) {
					String rhosync_session = extractToc("rhosync_session",
							header_field);
					cookie.strSession = rhosync_session;
					System.out.println("Extracted rhosync_session: "
							+ rhosync_session);
				}
			}
		}
		
		return cookie;
	}

	public static boolean fetch_client_login(String strUser, String strPwd) {
		boolean success = true;
		RubyArray sources = getSourceList();
		for (int i = 0; i < sources.size(); i++) {
			String strSession="";
			// String strExpire="";
			HttpURLConnection connection = null;
			
			RubyHash element = (RubyHash) sources.at(SyncUtil.createInteger(i));
			String sourceUrl = element.get(PerstLiteAdapter.SOURCE_URL)
					.toString();
			int id = element.get(PerstLiteAdapter.SOURCE_ID).toInt();

			if (sourceUrl.length() == 0 )
				continue;
			
			strSession = getSessionByDomain(sourceUrl);
			if ( strSession.length() == 0 ){
				ByteArrayInputStream dataStream = null;
				try {
					String body = "login=" + strUser + "&password=" + strPwd+ "&remember_me=1";
					dataStream = new ByteArrayInputStream(body.getBytes()); 
					
					SyncManager.makePostRequest(sourceUrl + "/client_login", dataStream, "",
							"application/x-www-form-urlencoded");
		
					connection = SyncManager.getConnection(); 
					int code = connection.getResponseCode();
					if (code == IHttpConnection.HTTP_OK) {
						ParsedCookie cookie = makeCookie(connection);
						strSession = cookie.strAuth + ";" + cookie.strSession
								+ ";";
					} else {
						System.out.println("Error posting data: " + code);
			            success = false;
					}

				} catch (IOException e) {
					System.out
							.println("There was an error fetch_client_login: "
							+ e.getMessage());
				} finally {
					
					if ( dataStream != null ){
						try{dataStream.close();}catch(IOException exc){}
						dataStream = null;
					}
					
					SyncManager.closeConnection();
					connection = null;
				}
			}

			RubyHash values = SyncUtil.createHash();
			values.add(PerstLiteAdapter.SESSION, createString(strSession));
			RubyHash where = SyncUtil.createHash();
			where.add(PerstLiteAdapter.SOURCE_ID, createInteger(id));
			
			adapter.updateIntoTable(createString(SyncConstants.SOURCES_TABLE),
					values, where);
		}
		
		return success;
	}
	
	public static String get_client_db_info(String attr) {
		RubyArray arr = createArray();
		arr.add(createString("client_info")); // table name
		arr.add(createString(attr)); // attributes
		arr.add(RubyConstant.QNIL); // where

		RubyArray results = (RubyArray)adapter.selectFromTable(arr);
		if ( results.size() > 0 ){
			RubyHash item = (RubyHash)results.get(0);
			RubyValue value = item.getValue(createString(attr)); 
			return value.toString();
		}
		return "";
	}

	public static boolean logged_in() {
		boolean success = false;
		RubyArray sources = SyncUtil.getSourceList();
		for (int i = 0; i < sources.size(); i++) {
			RubyHash element = (RubyHash) sources.at(SyncUtil.createInteger(i));
			String url = element.get(PerstLiteAdapter.SOURCE_URL).toString();
			int id = element.get(PerstLiteAdapter.SOURCE_ID).toInt();
			SyncSource current = new SyncSource(url, id);
			if (get_session(current).length() > 0) {
				success = true;
			}
		}
		return success;
	}

	public static void logout() {
		RubyArray sources = SyncUtil.getSourceList();
		for (int i = 0; i < sources.size(); i++) {
			RubyHash element = (RubyHash) sources.at(SyncUtil.createInteger(i));
			int id = element.get(PerstLiteAdapter.SOURCE_ID).toInt();
			RubyHash values = SyncUtil.createHash();
			values.add(PerstLiteAdapter.SESSION, SyncUtil.createString(""));
			RubyHash where = SyncUtil.createHash();
			where.add(PerstLiteAdapter.SOURCE_ID, createInteger(id));
			adapter.updateIntoTable(createString(SyncConstants.SOURCES_TABLE),
					values, where);
		}
	}

	public static void resetSyncDb() {
		adapter.deleteAllFromTable(createString(SyncConstants.CLIENT_INFO));
		adapter.deleteAllFromTable(createString(SyncConstants.OBJECTS_TABLE));
	}
}
