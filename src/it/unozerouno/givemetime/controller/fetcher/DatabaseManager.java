package it.unozerouno.givemetime.controller.fetcher;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.TimeZone;

import com.google.android.gms.drive.internal.am;
import com.google.android.gms.internal.da;
import com.google.android.gms.internal.fe;
import com.google.android.gms.internal.ne;
import com.google.android.gms.internal.nu;

import it.unozerouno.givemetime.controller.fetcher.CalendarFetcher.Actions;
import it.unozerouno.givemetime.controller.fetcher.places.PlaceFetcher;
import it.unozerouno.givemetime.controller.fetcher.places.PlaceFetcher.PlaceResult;
import it.unozerouno.givemetime.model.UserKeyRing;
import it.unozerouno.givemetime.model.constraints.ComplexConstraint;
import it.unozerouno.givemetime.model.constraints.Constraint;
import it.unozerouno.givemetime.model.constraints.DateConstraint;
import it.unozerouno.givemetime.model.constraints.DayConstraint;
import it.unozerouno.givemetime.model.constraints.TimeConstraint;
import it.unozerouno.givemetime.model.events.EventCategory;
import it.unozerouno.givemetime.model.events.EventDescriptionModel;
import it.unozerouno.givemetime.model.events.EventInstanceModel;
import it.unozerouno.givemetime.model.events.EventListener;
import it.unozerouno.givemetime.model.events.EventModel;
import it.unozerouno.givemetime.model.places.PlaceModel;
import it.unozerouno.givemetime.utils.CalendarUtils;
import it.unozerouno.givemetime.utils.GiveMeLogger;
import it.unozerouno.givemetime.utils.Results;
import it.unozerouno.givemetime.utils.TaskListener;
import it.unozerouno.givemetime.view.utilities.OnDatabaseUpdatedListener;
import android.R.bool;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteTransactionListener;
import android.location.Location;
import android.net.Uri;
import android.os.AsyncTask;
import android.provider.CalendarContract;
import android.provider.CalendarContract.Events;
import android.text.format.Time;
import android.text.style.BulletSpan;
import android.util.SparseArray;
import android.widget.Toast;

/**
 * This is the entry Point for the model. It fetches all stored app data from DB
 * and generates Model. It also keeps the internal GiveMeTime db and the
 * CalendarProvider synchronized by fetching updates from Google calendar and
 * updating the internal db.
 * 
 * @author Edoardo Giacomello
 * @author Paolo Bassi
 * 
 */
public final class DatabaseManager {

	private static SQLiteDatabase database = null;
	private static DatabaseCreator dbCreator;
	private static DatabaseManager dbManagerInstance;

	private DatabaseManager(Context context) {
		if (database == null || dbCreator == null) {
			dbCreator = DatabaseCreator.createHelper(context);
			database = dbCreator.getWritableDatabase();
		}
	}

	public static synchronized DatabaseManager getInstance(Context context) {
		if (dbManagerInstance == null) {
			dbManagerInstance = new DatabaseManager(context);
		}
		return dbManagerInstance;
	}

	/**
	 * close all instances of DB and DBHelper
	 */

	public static void closeDB() {
		if (database != null) {
			database.close();
		}
		if (dbCreator != null) {
			dbCreator.close();
		}
	}

	/**
	 * return a list of EventInstanceModel to be used by the calendar view
	 * inside two time constraints
	 * 
	 * @param start
	 * @param end
	 * @return
	 */
	public void getEventsInstances(Time start, Time end, Activity caller,
			final EventListener<EventInstanceModel> eventListener) {

		// fetch the event from the calendar provider
		final CalendarFetcher calendarFetcher = new CalendarFetcher(caller);
		calendarFetcher.setAction(CalendarFetcher.Actions.LIST_OF_EVENTS);
		calendarFetcher.setEventInstanceTimeQuery(start.toMillis(false),
				end.toMillis(false));
		calendarFetcher.setListener(new TaskListener<String[]>(caller) {
			SparseArray<EventDescriptionModel> eventDescriptionMap = new SparseArray<EventDescriptionModel>();

			@Override
			public void onTaskResult(String[]... results) {
				if (results[0] == Results.RESULT_TYPE_EVENT) {
					EventDescriptionModel newEvent = eventDescriptionToModel(results[1]);
					eventDescriptionMap.put(Integer.parseInt(newEvent.getID()),
							newEvent);
				} else if (results[0] == Results.RESULT_TYPE_INSTANCE) {
					EventInstanceModel newInstance = eventInstanceToModel(
							results[1], eventDescriptionMap);
					newInstance.addListener(eventListener);
					newInstance.setCreated();
				} else {
					// Unexpected result
					System.out
							.println("Got unexpected result from calendarFetcher");
				}

			}

		});

		calendarFetcher.execute();
	}

	/**
	 * Converts a CalendarFetcher string into an EventDescriptionModel
	 * Also loads GiveMeTime additional data, if present in database
	 * @param eventResult
	 * @return
	 */
	private EventDescriptionModel eventDescriptionToModel(String[] eventResult) {
		// Returned Values: 0:Events._ID, 1:Events.TITLE, 2:Events.DTSTART,
		// 3:Events.DTEND, 4:Events.EVENT_COLOR, 5:Events.RRULE, 6:Events.RDATE,
		// 7: Events.ALL_DAY
		// put each event inside a EventDescriptionModel
		System.out.println("Fetched event: id - " + eventResult[0]
				+ " Title - " + eventResult[1] + "  Start: " + eventResult[2]
				+ " End: " + eventResult[3] + " Color:" + eventResult[4]
				+ " RRULE:" + eventResult[5] + " RDATE: " + eventResult[6]
				+ " ALL_DAY: " + eventResult[7]);
		// prepare the model
		String id = eventResult[0];
		String title = eventResult[1];
		String start = eventResult[2];
		String end = eventResult[3];
		String color = eventResult[4];
		String RRULE = eventResult[5];
		String RDATE = eventResult[6];
		String ALL_DAY = eventResult[7];

		EventDescriptionModel eventDescriptionModel = new EventDescriptionModel(
				id, title);
		Long startLong = null;
		Long endLong = null;

		if (start != null) {
			startLong = Long.parseLong(start);
			eventDescriptionModel.setSeriesStartingDateTime(CalendarUtils
					.longToTime(startLong));
		}
		if (end != null) {
			endLong = Long.parseLong(end);
			eventDescriptionModel.setSeriesEndingDateTime(CalendarUtils
					.longToTime(endLong));
		}

		if (color != null) {
			eventDescriptionModel.setColor(Integer.parseInt(color));
		}
		// check for recursive events
		if (RRULE != null) {
			eventDescriptionModel.setRRULE(RRULE);
		}
		if (RDATE != null) {
			eventDescriptionModel.setRDATE(RDATE);
		}
		if (ALL_DAY != null) {
			eventDescriptionModel.setAllDay(Integer.parseInt(ALL_DAY));
		}
		
		//Loads GiveMeTime event Information
		return loadEventFromDatabase(eventDescriptionModel);
	}

	/**
	 * Converts a CalendarFetcher string into an EventInstanceModel
	 * 
	 * @param instanceResult
	 *            the CalendarFetcher result
	 * @param eventLookupTable
	 *            an HashMap containing known events from which select the one
	 *            to associate to the instance
	 * @return
	 */
	private EventInstanceModel eventInstanceToModel(String[] instanceResult,
			SparseArray<EventDescriptionModel> eventLookupTable) {
		int eventId = Integer.parseInt(instanceResult[0]);
		Time startTime = new Time();
		Time endTime = new Time();
		Long startLong = Long.parseLong(instanceResult[1]);
		Long endLong = Long.parseLong(instanceResult[2]);
		startTime.set(startLong);
		endTime.set(endLong);
		EventInstanceModel eventInstance = new EventInstanceModel(
				eventLookupTable.get(eventId), startTime, endTime);
		if (eventInstance.getEvent() == null) {
			System.err
					.println("Found an orphan instance without no event, looking for event id "
							+ eventId);
		}

		return eventInstance;

	}

	/**
	 * Pulls all new events from Google Calendar and creates relative entries in
	 * GiveMeTime database
	 */
	public static synchronized boolean synchronize(Activity caller) {

		// Fetching Events ID from CalendarProvider
		final CalendarFetcher calendarFetcher = new CalendarFetcher(caller);
		calendarFetcher.setAction(CalendarFetcher.Actions.LIST_EVENTS_ID_RRULE);
		calendarFetcher.setListener(new TaskListener<String[]>(caller) {
			@Override
			public void onTaskResult(String[]... results) {
				for (String[] event : results) {
					String eventId = event[0];
					String eventRRULE = event[1];
					String eventRDATE = event[2];
					DatabaseManager.getInstance(calendarFetcher.getCaller())
							.createEmptyEventRow(calendarFetcher.getCaller(),
									eventId);
					System.out.println("Created in DB event with id: "
							+ eventId + " RRULE: " + eventRRULE + " RDATE: "
							+ eventRDATE);
				}
			}

		});
		calendarFetcher.execute();

		return true;
		// TODO: synchronization: update of modified events while app was not
		// running
	}

	public static void updateEvent(Activity caller,
			EventInstanceModel eventToUpdate) {
		// Updating CalendarFetcher
		CalendarFetcher updater = new CalendarFetcher(caller);
		updater.setEventToUpdate(eventToUpdate);
		updater.setAction(Actions.UPDATE_EVENT);
		updater.setListener(new TaskListener<String[]>(caller) {
			@Override
			public void onTaskResult(String[]... results) {
				if (results[0] == Results.RESULT_OK) {
					System.out.println("Event Update complete");
				} else {
					System.out.println("Error during event update");
				}

			}
		});

		addEventInDatabase(eventToUpdate.getEvent().getID(), eventToUpdate);
	}

	/**
	 * Adds a new event into the db
	 */
	public static void addEvent(final Activity caller,
			final EventInstanceModel newEvent) {

		CalendarFetcher updater = new CalendarFetcher(caller);
		updater.setEventToUpdate(newEvent);
		updater.setAction(Actions.ADD_NEW_EVENT);
		updater.setListener(new TaskListener<String[]>(caller) {

			@Override
			public void onTaskResult(String[]... results) {
				String addedEventId = results[0][0];
				System.out.println("Event added with id " + addedEventId);
				addEventInDatabase(addedEventId, newEvent);
			}

		});
		
		updater.execute();
	}

	/**
	 * Adds a new Row in GiveMeTime database corresponding to a new event. Note
	 * that the event must be already present on the Calendar Provider AND its
	 * relative ID must be supplied.
	 * Note that if a row with the same EventID already exists, IT WILL BE REPLACED
	 * 
	 * @param addedEventId
	 *            the Id of the event in Calendar Provider
	 * @param newEvent
	 *            the event Model to add to GiveMeTime database
	 */
	private static void addEventInDatabase(String addedEventId,
			EventInstanceModel newEventInstance) {

		// TODO: Event addition
		EventDescriptionModel newEvent = newEventInstance.getEvent();
		String eventId = addedEventId;
		int calendarId = Integer.parseInt(newEvent.getCalendarId());
		int eventCategory = 0; //TODO: Manage categories
		String placeId = null;
		if(newEvent.getPlace() != null){
			placeId = newEvent.getPlace().getPlaceId();
		}
		
		boolean doNotDisturb = newEvent.getDoNotDisturb();
		boolean hasDeadline = newEvent.getHasDeadline();
		boolean isMovable = newEvent.getIsMovable();
		
		ContentValues values = new ContentValues();
		values.put(DatabaseCreator.ID_CALENDAR, calendarId);
		values.put(DatabaseCreator.ID_EVENT_PROVIDER, eventId);
		values.put(DatabaseCreator.ID_EVENT_CATEGORY, eventCategory);
		values.put(DatabaseCreator.ID_PLACE, placeId);
		values.put(DatabaseCreator.FLAG_DO_NOT_DISTURB, doNotDisturb);
		values.put(DatabaseCreator.FLAG_DEADLINE, hasDeadline);
		values.put(DatabaseCreator.FLAG_MOVABLE, isMovable);
		
		Long result = database.insertWithOnConflict(DatabaseCreator.TABLE_EVENT_MODEL, null, values, SQLiteDatabase.CONFLICT_REPLACE);
		System.out.println("Updated GiveMeTime DB row: " + result);
		
	}

	/**
	 * This function fills the provided EventDescriptionModel with information present into GiveMeTime database.
	 * @param eventToLoad
	 * @return
	 */
	private EventDescriptionModel loadEventFromDatabase (EventDescriptionModel eventToLoad){
		//TODO: Load event
		return eventToLoad;
	}

	/**
	 * At the beginning of the flow, adds in the EventDescriptionModel an EMPTY
	 * row for each event found in the provider
	 * 
	 * @param context
	 * @param eventId
	 *            the id fetcher in the provider
	 */

	private void createEmptyEventRow(Context context, String eventId) {
		String calId = UserKeyRing.getCalendarId(context);
		String CREATE_NEW_EMPTY_EVENT = "INSERT INTO "
				+ DatabaseCreator.TABLE_EVENT_MODEL + " ("
				+ DatabaseCreator.ID_CALENDAR + ", "
				+ DatabaseCreator.ID_EVENT_PROVIDER + ") " + "SELECT '"
				+ Integer.parseInt(calId) + "', '" + Integer.parseInt(eventId)
				+ "' " + "WHERE NOT EXISTS (" + "SELECT * FROM "
				+ DatabaseCreator.TABLE_EVENT_MODEL + " WHERE "
				+ DatabaseCreator.ID_CALENDAR + " = '" + calId + "' AND "
				+ DatabaseCreator.ID_EVENT_PROVIDER + " = '" + eventId + "'); ";
		database.execSQL(CREATE_NEW_EMPTY_EVENT);

	}

	// ///////////////////
	//
	// Location management
	//
	// ///////////////////

	/**
	 * Fetch more informations about Place result and store data into the
	 * database. When the operation is complete, it notify to a provided
	 * listener
	 * 
	 * @param placeResult
	 *            input Result
	 * @param OnDatabaseUpdatedListener
	 *            Listener to notify
	 */
	public static void addPlaceAndFetchInfo(PlaceResult placeResult,
			final OnDatabaseUpdatedListener listener) {
		PlaceModel newPlace = new PlaceModel(placeResult);

		AsyncTask<PlaceModel, Void, PlaceModel> placeFetcher = new AsyncTask<PlaceModel, Void, PlaceModel>() {

			@Override
			protected PlaceModel doInBackground(PlaceModel... place) {
				place[0] = PlaceFetcher.getAdditionalInfo(place[0]);
				return place[0];
			}

			@Override
			protected void onPostExecute(PlaceModel result) {
				super.onPostExecute(result);
				addPlaceInDatabase(result);
				listener.updateFinished();
			}
		};
		placeFetcher.execute(newPlace);
	}

	/**
	 * Store a placeModel into the database
	 * 
	 * @param newPlace
	 */
	private static void addPlaceInDatabase(PlaceModel newPlace) {
		// Now the place has all known infos

		// Getting whole place data
		String placeId = newPlace.getPlaceId();
		String name = newPlace.getName();
		String address = newPlace.getAddress();
		String formattedAddress = newPlace.getFormattedAddress();
		String country = newPlace.getCountry();
		String phoneNumber = newPlace.getPhoneNumber();
		String icon = newPlace.getIcon();
		Double latitude = newPlace.getLocation().getLatitude();
		Double longitude = newPlace.getLocation().getLongitude();
		int visitCounter = newPlace.getVisitCounter();

		// Insering Values is PlaceModel table
		ContentValues values = new ContentValues();
		values.put(DatabaseCreator.PLACE_ID, placeId);
		values.put(DatabaseCreator.PLACE_NAME, name);
		values.put(DatabaseCreator.PLACE_ADDRESS, address);
		values.put(DatabaseCreator.PLACE_FORMATTED_ADDRESS, formattedAddress);
		values.put(DatabaseCreator.PLACE_COUNTRY, country);
		values.put(DatabaseCreator.PLACE_PHONE_NUMBER, phoneNumber);
		values.put(DatabaseCreator.PLACE_ICON, icon);
		values.put(DatabaseCreator.PLACE_LOCATION_LATITUDE,
				Double.toString(latitude));
		values.put(DatabaseCreator.PLACE_LOCATION_LONGITUDE,
				Double.toString(longitude));
		values.put(DatabaseCreator.PLACE_VISIT_COUNTER,
				Integer.toString(visitCounter));
		Time now = new Time();
		now.setToNow();
		values.put(DatabaseCreator.PLACE_DATE_CREATED, now.toMillis(false));

		// Executing Query
		Long query = database.insertWithOnConflict(
				DatabaseCreator.TABLE_PLACE_MODEL, null, values,
				SQLiteDatabase.CONFLICT_REPLACE);
		System.out.println("Inserted Location, added row: " + query);

		// TODO: update constraints
		addConstraints(newPlace);
	}

	public static List<PlaceModel> getLocations() {
		List<PlaceModel> places = new ArrayList<PlaceModel>();
		String[] projection = DatabaseCreator.Projections.PLACES_ALL;
		Cursor cursor = database.query(DatabaseCreator.TABLE_PLACE_MODEL,
				projection, null, null, null, null,
				DatabaseCreator.PLACE_DATE_CREATED + ", "
						+ DatabaseCreator.PLACE_VISIT_COUNTER + " DESC");
		while (cursor.moveToNext()) {
			String placeId = cursor.getString(DatabaseCreator.Projections
					.getIndex(projection, DatabaseCreator.PLACE_ID));
			String name = cursor.getString(DatabaseCreator.Projections
					.getIndex(projection, DatabaseCreator.PLACE_NAME));
			String address = cursor.getString(DatabaseCreator.Projections
					.getIndex(projection, DatabaseCreator.PLACE_ADDRESS));
			String formattedAddress = cursor
					.getString(DatabaseCreator.Projections.getIndex(projection,
							DatabaseCreator.PLACE_FORMATTED_ADDRESS));
			String country = cursor.getString(DatabaseCreator.Projections
					.getIndex(projection, DatabaseCreator.PLACE_COUNTRY));
			String phoneNumber = cursor.getString(DatabaseCreator.Projections
					.getIndex(projection, DatabaseCreator.PLACE_PHONE_NUMBER));
			String icon = cursor.getString(DatabaseCreator.Projections
					.getIndex(projection, DatabaseCreator.PLACE_ICON));

			String latitudeString = cursor
					.getString(DatabaseCreator.Projections.getIndex(projection,
							DatabaseCreator.PLACE_LOCATION_LATITUDE));
			Double latitude = Double.parseDouble(latitudeString);
			String longitudeString = cursor
					.getString(DatabaseCreator.Projections.getIndex(projection,
							DatabaseCreator.PLACE_LOCATION_LONGITUDE));
			Double longitude = Double.parseDouble(longitudeString);
			String visitCounterString = cursor
					.getString(DatabaseCreator.Projections.getIndex(projection,
							DatabaseCreator.PLACE_VISIT_COUNTER));
			int visitCounter = Integer.parseInt(visitCounterString);

			// Creating PlaceModel
			PlaceModel newPlace = new PlaceModel(placeId, name, address,
					country);
			newPlace.setFormattedAddress(formattedAddress);
			newPlace.setPhoneNumber(phoneNumber);
			newPlace.setIcon(icon);
			Location newLocation = new Location("GiveMeTime");
			newLocation.setLatitude(latitude);
			newLocation.setLongitude(longitude);
			newPlace.setLocation(newLocation);
			newPlace.setVisitCounter(visitCounter);

			List<ComplexConstraint> openingTimes = getConstraints(newPlace);
			newPlace.setOpeningTime(openingTimes);
			places.add(newPlace);
		}
		cursor.close();
		return places;
	}

	// ///////////////////////
	//
	//
	// Constraints functions
	//
	//
	// ///////////////////////

			//////////////////////////
			// Constraints Insert/Update
			/////////////////////////
	private static void addComplexConstraintInDatabase(ComplexConstraint complexConstraint){
		SparseArray<Constraint> addedConstraintIndexes = new SparseArray<Constraint>();
		for (Constraint currentConstraint : complexConstraint.getConstraints()) {
		   int currentId = addSimpleConstratint(currentConstraint);
		   addedConstraintIndexes.put(currentId,currentConstraint);
		}
		//Now simple constraints are generated and we have a map for id-constraint
		//Inserting rows into ComplexConstraint table
		
			int complexConstraintID = complexConstraint.getId();
		
			//we have to update an existing ComplexConstraint 
			//Removing all existing rows of the complexConstraint (so even removed simpleConstraints are deleted)
			database.delete(DatabaseCreator.TABLE_COMPLEX_CONSTRAINTS, DatabaseCreator.C_COMPLEX_ID + " = " + complexConstraint.getId(), null);
			
			
			//Adding all the new rows
			for (int i = 0; i < addedConstraintIndexes.size(); i++) {
			int currentSimpleConstraintId = addedConstraintIndexes.keyAt(i);
			ContentValues values = new ContentValues();
			values.put(DatabaseCreator.C_COMPLEX_S_ID, currentSimpleConstraintId);
			
			if (complexConstraintID != -1){
				//If the complexConstraintID is -1 then we are adding a new complexConstraint in the db
				//so we don't specify the constraint for the first insertion
				values.put(DatabaseCreator.C_COMPLEX_ID, complexConstraint.getId());
			}
			//Here we add a new row into ComplexConstaint table
				Long rowIndex = database.insert(DatabaseCreator.TABLE_COMPLEX_CONSTRAINTS, null, values);
				
			if (complexConstraintID==-1){
				//If the constraintID is still -1 we can get back the actual complexConstraintID value from the database
				String where = "rowid = " + rowIndex;
				String[] projection = {DatabaseCreator.C_COMPLEX_ID};
				Cursor result = database.query(DatabaseCreator.TABLE_COMPLEX_CONSTRAINTS, projection , where,null, null, null, null);
				while (result.moveToNext()) {
					//Here we're updating the complexCID with the value generated by the database
					complexConstraintID = result.getInt(0);
				}
				result.close();
			}
				System.out.println("Added complex constraint with id " + complexConstraint.getId() + ", parent of a single event with id" + currentSimpleConstraintId );
		}
		
			
			
		
		
	}
	/**
	 * Add a simpleConstraint in the relative database table
	 * @param constraintToAdd
	 * @return the id of the added/updated constraint
	 */
	private static int addSimpleConstratint(Constraint constraintToAdd){
		int id = -1;
		String type;
		Long start;
		Long end;
		int dayStart;
		int dayEnd;
		
		if (constraintToAdd instanceof DateConstraint){
			DateConstraint constraint = (DateConstraint) constraintToAdd;
			type = "DateConstraint"; 
			//Checking if given constraint has id or not (update or insert)
			if(constraint.getId() == -1){
				id = getNewSimpleConstraintId();
			} else {
				id = constraint.getId();
			}
			start = constraint.getStartingDate().toMillis(false);
			end = constraint.getEndingDate().toMillis(false);
			//Adding constraint to Database
			addSimpleConstraintRow(id, type, start.toString(), end.toString());
			return id;
		}
		if (constraintToAdd instanceof TimeConstraint){
			TimeConstraint constraint = (TimeConstraint) constraintToAdd;
			type = "TimeConstraint"; 
			//Checking if given constraint has id or not (update or insert)
			if(constraint.getId() == -1){
				id = getNewSimpleConstraintId();
			} else {
				id = constraint.getId();
			}
			start = constraint.getStartingTime().toMillis(false);
			end = constraint.getEndingTime().toMillis(false);
			//Adding constraint to Database
			addSimpleConstraintRow(id, type, start.toString(), end.toString());
			return id;
		}
		if (constraintToAdd instanceof DayConstraint){
			DayConstraint constraint = (DayConstraint) constraintToAdd;
			type = "DayConstraint"; 
			//Checking if given constraint has id or not (update or insert)
			if(constraint.getId() == -1){
				id = getNewSimpleConstraintId();
			} else {
				id = constraint.getId();
			}
			dayStart = constraint.getStartingDay();
			dayEnd = constraint.getEndingDay();
			//Adding constraint to Database
			addSimpleConstraintRow(id, type, Integer.toString(dayStart), Integer.toString(dayEnd));
			return id;
		}
		System.out.println("This function cannot parse this constraint");
		return -1;
	}
	/**
	 * This function adds/update a single row in SimpleConstraint table of GiveMeTime DB
	 * @param id
	 * @param constraintType
	 * @param start
	 * @param end
	 */
	private static synchronized void addSimpleConstraintRow(int id, String constraintType, String start, String end){
		ContentValues values = new ContentValues();
		values.put(DatabaseCreator.C_SIMPLE_ID_CONSTRAINT, id);
		values.put(DatabaseCreator.C_SIMPLE_CONSTRAINT_TYPE, constraintType);
		values.put(DatabaseCreator.C_START, start);
		values.put(DatabaseCreator.C_END, end);
		Long query = database.insertWithOnConflict(DatabaseCreator.TABLE_SIMPLE_CONSTRAINTS, null, values, SQLiteDatabase.CONFLICT_REPLACE);
		System.out.println("Added/Edited row " + query + "of SimpleConstraint table");
	}
	
	/**
	 * This function inserts a new empty row in SimpleConstraints table and returns the ID for the update
	 * @return
	 */
	private static synchronized int getNewSimpleConstraintId(){
		//TODO: implement this
		Long rowIndex = database.insert(DatabaseCreator.TABLE_SIMPLE_CONSTRAINTS, null, new ContentValues());
		String where = "rowid = " + rowIndex;
		String[] projection = {DatabaseCreator.C_SIMPLE_ID_CONSTRAINT};
		Cursor result = database.query(DatabaseCreator.TABLE_SIMPLE_CONSTRAINTS, projection , where,null, null, null, null);
		int newId=-1;
		while (result.moveToNext()) {
			newId = result.getInt(0);
		}
		result.close();
		return newId;
	}
	
	/**
	 * This get the opening time of a particular place in the db. Note that they
	 * are not put directly in the PlaceModel object but returned instead.
	 * 
	 * @param place
	 * @return
	 */
    public static List<ComplexConstraint> getConstraints(PlaceModel place) {
		List<ComplexConstraint> constraints = new ArrayList<ComplexConstraint>();
		// TODO: fetch constraints
		return constraints;
	}

	/**
	 * This get the constraints of a particular event in the db. Note that they
	 * are not put directly in the Event Model object but returned instead.
	 * 
	 * @param event
	 * @return
	 */
	public static List<ComplexConstraint> getConstraints(EventModel event) {
		List<ComplexConstraint> constraints = new ArrayList<ComplexConstraint>();
		// TODO: fetch constraints
		return constraints;
	}

	/**
	 * This function adds opening time of a particular places in db
	 * 
	 * @param place
	 */
	public static void addConstraints(PlaceModel place) {
		// TODO: Set constraints
	}

	/**
	 * Adds event constraint in db
	 * 
	 * @param constraints
	 */
	public static void addConstraints(EventDescriptionModel event) {
		//Adding constraints to database
		//TODO: adding ComplexConstraints to EventConstraint table
		
		
	}
	
	// ///////////////////////
	//
	//
	// EventCategory functions
	//
	//
	// ///////////////////////
	
	/**
	 * This functions addds the default categories into the GiveMeTime database
	 */
	public static void addDefaultCategories(){
		//Default categories
		EventCategory workCategory = new EventCategory("Work", false, true);
		//They are default category, thus they cannot be removed by the user
		workCategory.setDefaultCategory(true);
		EventCategory errandsCategory = new EventCategory("Errands (Movable)", true, false);
		errandsCategory.setDefaultCategory(true);
		EventCategory amusementCategory = new EventCategory("Amusement", false, false);
		amusementCategory.setDefaultCategory(true);
		
		List<EventCategory> defaultCategories = new ArrayList<EventCategory>();
		
		defaultCategories.add(workCategory);
		defaultCategories.add(errandsCategory);
		defaultCategories.add(amusementCategory);
		
		//Adding categories to database
		for (EventCategory eventCategory : defaultCategories) {
			ContentValues values = new ContentValues();
			values.put(DatabaseCreator.ECA_NAME, eventCategory.getName());
			values.put(DatabaseCreator.ECA_DEFAULT_DONOTDISTURB, eventCategory.isDefault_donotdisturb());
			values.put(DatabaseCreator.ECA_DEFAULT_MOVABLE, eventCategory.isDefault_movable());
			values.put(DatabaseCreator.ECA_DEFAULT_CATEGORY, eventCategory.isDefaultCategory());
			
			//TODO: Check if this update policy erases categories from the EVENT_MODEL table when they update
			Long query = database.insertWithOnConflict(DatabaseCreator.TABLE_EVENT_CATEGORY, null, values, SQLiteDatabase.CONFLICT_REPLACE);
			System.out.println("Added default constraint row: " + query);
		}
		
	}
	public static void addCategory(EventCategory category){
				//Adding category to database
					ContentValues values = new ContentValues();
					values.put(DatabaseCreator.ECA_NAME, category.getName());
					values.put(DatabaseCreator.ECA_DEFAULT_DONOTDISTURB, category.isDefault_donotdisturb());
					values.put(DatabaseCreator.ECA_DEFAULT_MOVABLE, category.isDefault_movable());
					values.put(DatabaseCreator.ECA_DEFAULT_CATEGORY, category.isDefaultCategory());
					
					//TODO: Check if this update policy erases categories from the EVENT_MODEL table when they update
					Long query = database.insertWithOnConflict(DatabaseCreator.TABLE_EVENT_CATEGORY, null, values, SQLiteDatabase.CONFLICT_REPLACE);
					System.out.println("Added default constraint row: " + query);
	}
	
	/**
	 */
	public static List<EventCategory> getCategories(){
		List<EventCategory> categories = new ArrayList<EventCategory>();
		
		String[] projection = DatabaseCreator.Projections.ECA_ALL;
		String table = DatabaseCreator.TABLE_EVENT_CATEGORY;
		String orderBy = DatabaseCreator.ECA_DEFAULT_CATEGORY+", "+ DatabaseCreator.ECA_NAME;
		
		Cursor fetchedCategories = database.query(table, projection, null, null, null, null, orderBy);
			while (fetchedCategories.moveToNext()) {
				String name = fetchedCategories.getString(DatabaseCreator.Projections.getIndex(projection, DatabaseCreator.ECA_NAME));				
				String defaultDoNotDisturb = fetchedCategories.getString(DatabaseCreator.Projections.getIndex(projection, DatabaseCreator.ECA_DEFAULT_DONOTDISTURB));
				String defaultMovable = fetchedCategories.getString(DatabaseCreator.Projections.getIndex(projection, DatabaseCreator.ECA_DEFAULT_MOVABLE));
				String defaultCategory = fetchedCategories.getString(DatabaseCreator.Projections.getIndex(projection, DatabaseCreator.ECA_DEFAULT_CATEGORY));
				
				EventCategory newCategory = new EventCategory(name, Boolean.parseBoolean(defaultMovable), Boolean.parseBoolean(defaultDoNotDisturb));
				newCategory.setDefaultCategory(Boolean.parseBoolean(defaultCategory));
				categories.add(newCategory);
			}
		
		
		fetchedCategories.close();
		return categories;
	} 
	
	public static void deleteCategory(EventCategory categoryToDelete){
		String table = DatabaseCreator.TABLE_EVENT_CATEGORY;
		String where = DatabaseCreator.ECA_NAME + " = " + categoryToDelete.getName();
		int deleteQuery = database.delete(table, where, null);
		System.out.println("Deleted " + deleteQuery + " category rows.");
	}
	
	
	
	
	// /////////////////////////////
	//
	// Database
	//
	// /////////////////////////////

	/**
	 * This helper class creates the GiveMeTime database
	 * 
	 * @author Edoardo Giacomello <edoardo.giacomello1990@gmail.com> Paolo Bassi
	 * 
	 */
	private static class DatabaseCreator extends SQLiteOpenHelper {
		static class Projections {
			public static final String[] PLACES_ALL = { PLACE_ID, PLACE_NAME,
					PLACE_ADDRESS, PLACE_FORMATTED_ADDRESS, PLACE_COUNTRY,
					PLACE_PHONE_NUMBER, PLACE_ICON, PLACE_LOCATION_LONGITUDE,
					PLACE_LOCATION_LATITUDE, PLACE_VISIT_COUNTER,
					PLACE_DATE_CREATED };
			public static final String[] ECA_ALL = {ECA_NAME, ECA_DEFAULT_DONOTDISTURB, ECA_DEFAULT_MOVABLE, ECA_DEFAULT_CATEGORY};

			public static int getIndex(String[] projection, String coloumn) {
				int counter = 0;
				for (String currentColoumn : projection) {
					if (currentColoumn == coloumn)
						return counter;
					else {
						counter++;
					}
				}
				return -1;
			}
		}

		// Database Name
		private static final String DATABASE_NAME = "givemetime.db";
		// Database Version
		private static final int DATABASE_VERSION = 1;
		// Database Tables
		static final String TABLE_EVENT_MODEL = "event_model";
		static final String TABLE_PLACE_MODEL = "place_model";
		static final String TABLE_QUESTION_MODEL = "question_model";
		static final String TABLE_OPENING_TIMES = "opening_times";
		static final String TABLE_EVENT_CATEGORY = "event_category";
		static final String TABLE_EVENT_CONSTRAINTS = "event_constraints";
		static final String TABLE_SIMPLE_CONSTRAINTS = "simple_constraints";
		static final String TABLE_COMPLEX_CONSTRAINTS = "complex_constraints";
		static final String TABLE_USER_PREFERENCE = "user_preference";
		static final String TABLE_VACATION_DAYS = "vacation_days";
		static final String TABLE_WORK_TIMETABLE = "work_timetable";

		// Database Column Names
		// EVENT_MODEL
		private static final String ID_CALENDAR = "id_calendar";
		private static final String ID_EVENT_PROVIDER = "id_event_provider";
		private static final String ID_EVENT_CATEGORY = "id_event_category";
		private static final String ID_PLACE = "id_place";
		private static final String FLAG_DO_NOT_DISTURB = "do_not_disturb_flag";
		private static final String FLAG_DEADLINE = "flag_deadline";
		private static final String FLAG_MOVABLE = "flag_movable";
		// PLACE_MODEL
		private static final String PLACE_ID = "place_id";
		private static final String PLACE_NAME = "place_name";
		private static final String PLACE_ADDRESS = "place_address";
		private static final String PLACE_FORMATTED_ADDRESS = "place_formatted_address";
		private static final String PLACE_COUNTRY = "place_country";
		private static final String PLACE_PHONE_NUMBER = "place_phone_number";
		private static final String PLACE_ICON = "place_icon";
		private static final String PLACE_LOCATION_LATITUDE = "place_location_latitude";
		private static final String PLACE_LOCATION_LONGITUDE = "place_location_longitude";
		private static final String PLACE_VISIT_COUNTER = "place_visit_counter";
		private static final String PLACE_DATE_CREATED = "place_date_created";
		//OPENING_TIMES
		private static final String OT_PLACE_ID = "ot_place_id";
		private static final String OT_COMPLEX_CONSTRAINT = "ot_constraint_id";
		// QUESTION_MODEL
		private static final String ID_QUESTION = "id_question";
		private static final String DATE_TIME = "date_time";
		private static final String TYPE_QUESTION = "type_question";
		private static final String EVENT_ID = "event_id";
		private static final String USER_LOCATION = "user_location";
		// EVENT CATEGORY
		private static final String ECA_NAME = "eca_name";
		private static final String ECA_DEFAULT_DONOTDISTURB = "default_donotdisturb";
		private static final String ECA_DEFAULT_MOVABLE = "default_movable";
		private static final String ECA_DEFAULT_CATEGORY = "default_category";
		// EVENT CONSTRAINT
		private static final String ECO_ID_COMPLEX_CONSTRAINT = "eco_id_complex_constraint";
		private static final String ECO_ID_EVENT = "eco_id_event";
		// SIMPLE CONSTRAINTS
		private static final String C_SIMPLE_ID_CONSTRAINT = "c_simple_id_constraint";
		private static final String C_SIMPLE_CONSTRAINT_TYPE = "c_simple_constraint_type";
		private static final String C_START = "c_start";
		private static final String C_END = "c_end";
		// COMPLEX CONSTRAINTS
		private static final String C_COMPLEX_ID = "c_complex_id_constraint";
		private static final String C_COMPLEX_S_ID = "c_complex_simple_id";
		// USER_PREFERENCE
		private static final String ACCOUNT = "account";
		private static final String HOME_LOCATION = "home_location";
		private static final String ID_SLEEP_TIME = "id_sleep_time";
		// VACATION DAYS
		private static final String VD_ACCOUNT = "vd_account";
		private static final String VD_ID_CONSTRAINT = "vd_id_constraint";
		// WORK TIMETABLE
		private static final String WT_ACCOUNT = "wt_account";
		private static final String WT_ID_CONSTRAINT = "wt_id_constraint";

		// Table Create Statements
		// EVENT_MODEL
		private static final String CREATE_TABLE_EVENT_MODEL = "CREATE TABLE "
				+ TABLE_EVENT_MODEL + "(" + ID_CALENDAR + " INT NOT NULL, "
				+ ID_EVENT_PROVIDER + " INT NOT NULL, " + ID_EVENT_CATEGORY
				+ " VARCHAR(30), " + ID_PLACE + " VARCHAR(255), "
				+ FLAG_DO_NOT_DISTURB + " BOOLEAN, " + FLAG_DEADLINE
				+ " BOOLEAN, " + FLAG_MOVABLE + " BOOLEAN, " 
				+ " PRIMARY KEY (" + ID_CALENDAR + ", " + ID_EVENT_PROVIDER + "),"
				+ " FOREIGN KEY (" + ID_EVENT_CATEGORY + ") REFERENCES " + TABLE_EVENT_CATEGORY + " (" + ECA_NAME + ")"
				+ " FOREIGN KEY (" + ID_PLACE + ") REFERENCES " + TABLE_PLACE_MODEL + " (" + PLACE_ID + ")" 
				+ ");";
		// PLACE_MODEL
		private static final String CREATE_TABLE_PLACE_MODEL = "CREATE TABLE "
				+ TABLE_PLACE_MODEL + "(" + PLACE_ID
				+ " VARCHAR(255) PRIMARY KEY NOT NULL, " + PLACE_NAME
				+ " VARCHAR(50), " + PLACE_ADDRESS + " VARCHAR(50), "
				+ PLACE_FORMATTED_ADDRESS + " VARCHAR(255), " + PLACE_COUNTRY
				+ " VARCHAR(50), " + PLACE_PHONE_NUMBER + " VARCHAR(50), "
				+ PLACE_ICON + " VARCHAR(255), " + PLACE_LOCATION_LATITUDE
				+ " VARCHAR(50), " + PLACE_LOCATION_LONGITUDE
				+ " VARCHAR(50), " + PLACE_VISIT_COUNTER + " VARCHAR(50), "
				+ PLACE_DATE_CREATED + " VARCHAR(50)" + 
				" );";
		// OPENING_TIMES
		private static final String CREATE_TABLE_OPENING_TIMES = "CREATE TABLE "
						+ TABLE_OPENING_TIMES + "(" 
						+ OT_PLACE_ID + " VARCHAR(255), " 
						+ OT_COMPLEX_CONSTRAINT + " INTEGER NOT NULL,"
						+ " PRIMARY KEY ("+ OT_PLACE_ID + ", " + OT_COMPLEX_CONSTRAINT +"),"
						+ " FOREIGN KEY (" + OT_PLACE_ID + ") REFERENCES " + TABLE_PLACE_MODEL + "(" + PLACE_ID + ")"
						+ " FOREIGN KEY (" + OT_COMPLEX_CONSTRAINT + ") REFERENCES " + TABLE_COMPLEX_CONSTRAINTS + "(" + C_COMPLEX_ID + ")"
						+ " );";
		
		// QUESTION_MODEL
		private static final String CREATE_TABLE_QUESTION_MODEL = "CREATE TABLE "
				+ TABLE_QUESTION_MODEL
				+ "("
				+ ID_QUESTION
				+ " INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "
				+ DATE_TIME
				+ " DATE, "
				+ TYPE_QUESTION
				+ " VARCHAR(30), "
				+ EVENT_ID
				+ " INT, "
				+ USER_LOCATION
				+ " VARCHAR(255), "
				+ " FOREIGN KEY ("
				+ EVENT_ID
				+ ") REFERENCES "
				+ TABLE_EVENT_MODEL
				+ " ("
				+ ID_EVENT_PROVIDER
				+ ")"
				+ " FOREIGN KEY ("
				+ USER_LOCATION
				+ ") REFERENCES "
				+ TABLE_PLACE_MODEL + " (" + PLACE_ID + ")" + ");";
		// EVENT_CATEGORY
		private static final String CREATE_TABLE_EVENT_CATEGORY = "CREATE TABLE "
				+ TABLE_EVENT_CATEGORY
				+ "("
				+ ECA_NAME 	+ " VARCHAR(30) PRIMARY KEY, "	
				+ ECA_DEFAULT_DONOTDISTURB	+ " BOOLEAN," 
				+ ECA_DEFAULT_MOVABLE	+ " BOOLEAN," 
				+ ECA_DEFAULT_CATEGORY	+ " BOOLEAN" 
				+ ");";
		// EVENT_CONSTRAINTS
		private static final String CREATE_TABLE_EVENT_CONSTRAINTS = "CREATE TABLE "
				+ TABLE_EVENT_CONSTRAINTS
				+ "(" + ECO_ID_COMPLEX_CONSTRAINT + " INT, "
				+ ECO_ID_EVENT 	+ " INT, " + " PRIMARY KEY (" 
				+ ECO_ID_COMPLEX_CONSTRAINT + ", " + ECO_ID_EVENT + "),"
				+ " FOREIGN KEY (" + ECO_ID_COMPLEX_CONSTRAINT + ") REFERENCES " + TABLE_COMPLEX_CONSTRAINTS + " (" + C_COMPLEX_ID + ")"
				+ " FOREIGN KEY (" + ECO_ID_EVENT + ") REFERENCES "+ TABLE_EVENT_MODEL + " (" + ID_EVENT_PROVIDER + ")" 
				+ ");";
		// SIMPLE_CONSTRAINTS
		private static final String CREATE_TABLE_SIMPLE_CONSTRAINTS = "CREATE TABLE "
				+ TABLE_SIMPLE_CONSTRAINTS + "(" 
				+ C_SIMPLE_ID_CONSTRAINT + " INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "
				+ C_SIMPLE_CONSTRAINT_TYPE + " VARCHAR(30), " 
				+ C_START + " VARCHAR(30), " 
				+ C_END + " VARCHAR(30)" 
				+ ");";
		//COMPLEX_CONSTRAINTS
		private static final String CREATE_TABLE_COMPLEX_CONSTRAINTS = "CREATE TABLE "
						+ TABLE_COMPLEX_CONSTRAINTS + "(" 
						+ C_COMPLEX_ID + " INTEGER NOT NULL, "
						+ C_COMPLEX_S_ID + " INTEGER NOT NULL, " 
						+ " PRIMARY KEY ("+ C_COMPLEX_ID + ", " + C_COMPLEX_S_ID +"),"
						+ " FOREIGN KEY (" + C_COMPLEX_ID + ") REFERENCES " + TABLE_SIMPLE_CONSTRAINTS + "(" + C_SIMPLE_ID_CONSTRAINT + ")"
						+ ");";
		
		// USER_PREFERENCE
		private static final String CREATE_TABLE_USER_PREFERENCE = "CREATE TABLE "
				+ TABLE_USER_PREFERENCE + "("
				+ ACCOUNT + " VARCHAR(30) PRIMARY KEY, "
				+ HOME_LOCATION	+ " VARCHAR(255), "
				+ ID_SLEEP_TIME	+ " INT, "+ " FOREIGN KEY ("+ ACCOUNT+ ") REFERENCES "+ TABLE_VACATION_DAYS	+ " ("	+ VD_ACCOUNT+ ")"	
				+ " FOREIGN KEY ("+ HOME_LOCATION+ ") REFERENCES "+ TABLE_PLACE_MODEL+ " ("+ PLACE_ID+ ")"
				+ " FOREIGN KEY ("+ ID_SLEEP_TIME+ ") REFERENCES "+ TABLE_COMPLEX_CONSTRAINTS+ " ("+ C_COMPLEX_ID + ")" 
				+ ");";
		// VACATION_DAYS
		private static final String CREATE_TABLE_VACATION_DAYS = "CREATE TABLE "
				+ TABLE_VACATION_DAYS
				+ "("
				+ VD_ACCOUNT + " VARCHAR(30), "
				+ VD_ID_CONSTRAINT+ " INT, "+ 
				" PRIMARY KEY ("+ VD_ACCOUNT+ ", "+ VD_ID_CONSTRAINT+ "),"
				+ " FOREIGN KEY ("+ VD_ACCOUNT+ ") REFERENCES "+ TABLE_USER_PREFERENCE+ " ("+ ACCOUNT+ ")"
				+ " FOREIGN KEY ("+ VD_ID_CONSTRAINT+ ") REFERENCES "+ TABLE_COMPLEX_CONSTRAINTS+ " ("+ C_COMPLEX_ID+ ")" 
				+ ");";
		// WORK_TIMETABLE
		private static final String CREATE_TABLE_WORK_TIMETABLE = "CREATE TABLE "
				+ TABLE_WORK_TIMETABLE 	+ "("
				+ WT_ACCOUNT+ " VARCHAR(30), "
				+ WT_ID_CONSTRAINT+ " INT, "
				+ " PRIMARY KEY ("+ WT_ACCOUNT+ ", "+ WT_ID_CONSTRAINT+ "),"
				+ " FOREIGN KEY ("+ WT_ACCOUNT+ ") REFERENCES "+ TABLE_USER_PREFERENCE+ " ("+ ACCOUNT+ ")"
				+ " FOREIGN KEY ("+ WT_ID_CONSTRAINT+ ") REFERENCES "+ TABLE_COMPLEX_CONSTRAINTS+ " ("+ C_COMPLEX_ID+ ")" 
				+ ");";

		public static DatabaseCreator createHelper(Context context) {
			return new DatabaseCreator(context);
		}

		public DatabaseCreator(Context context) {
			super(context, DATABASE_NAME, null, DATABASE_VERSION);
		}

		@Override
		public void onCreate(SQLiteDatabase db) {

			// generate all the tables of the db
			db.execSQL(CREATE_TABLE_EVENT_MODEL);
			db.execSQL(CREATE_TABLE_PLACE_MODEL);
			db.execSQL(CREATE_TABLE_QUESTION_MODEL);
			db.execSQL(CREATE_TABLE_OPENING_TIMES);
			db.execSQL(CREATE_TABLE_EVENT_CATEGORY);
			db.execSQL(CREATE_TABLE_EVENT_CONSTRAINTS);
			db.execSQL(CREATE_TABLE_SIMPLE_CONSTRAINTS);
			db.execSQL(CREATE_TABLE_COMPLEX_CONSTRAINTS);
			db.execSQL(CREATE_TABLE_USER_PREFERENCE);
			db.execSQL(CREATE_TABLE_VACATION_DAYS);
			db.execSQL(CREATE_TABLE_WORK_TIMETABLE);

		}

		@Override
		public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {

			GiveMeLogger.log("Upgrading database from version " + oldVersion
					+ " to " + newVersion + " which will destroy all old data");
			// on upgrade drop older tables
			db.execSQL("DROP TABLE IF EXISTS " + TABLE_EVENT_MODEL);
			db.execSQL("DROP TABLE IF EXISTS " + TABLE_PLACE_MODEL);
			db.execSQL("DROP TABLE IF EXISTS " + TABLE_QUESTION_MODEL);
			db.execSQL("DROP TABLE IF EXISTS " + TABLE_OPENING_TIMES);
			db.execSQL("DROP TABLE IF EXISTS " + TABLE_EVENT_CATEGORY);
			db.execSQL("DROP TABLE IF EXISTS " + TABLE_EVENT_CONSTRAINTS);
			db.execSQL("DROP TABLE IF EXISTS " + TABLE_SIMPLE_CONSTRAINTS);
			db.execSQL("DROP TABLE IF EXISTS " + TABLE_COMPLEX_CONSTRAINTS);
			db.execSQL("DROP TABLE IF EXISTS " + TABLE_USER_PREFERENCE);
			db.execSQL("DROP TABLE IF EXISTS " + TABLE_VACATION_DAYS);
			db.execSQL("DROP TABLE IF EXISTS " + TABLE_WORK_TIMETABLE);

			// create new tables
			onCreate(db);

		}

	}
}
