/**
 * Copyright (C) 2011 Whisper Systems
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.thoughtcrime.securesms.database;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.annimon.stream.Collectors;
import com.annimon.stream.Stream;
import com.google.android.mms.pdu_alt.NotificationInd;
import com.google.android.mms.pdu_alt.PduHeaders;

import net.sqlcipher.database.SQLiteDatabase;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.session.libsession.messaging.messages.signal.IncomingMediaMessage;
import org.session.libsession.messaging.messages.signal.OutgoingExpirationUpdateMessage;
import org.session.libsession.messaging.messages.signal.OutgoingGroupMediaMessage;
import org.session.libsession.messaging.messages.signal.OutgoingMediaMessage;
import org.session.libsession.messaging.messages.signal.OutgoingSecureMediaMessage;
import org.session.libsession.messaging.sending_receiving.attachments.Attachment;
import org.session.libsession.messaging.sending_receiving.attachments.AttachmentId;
import org.session.libsession.messaging.sending_receiving.attachments.DatabaseAttachment;
import org.session.libsession.messaging.sending_receiving.link_preview.LinkPreview;
import org.session.libsession.messaging.sending_receiving.quotes.QuoteModel;
import org.session.libsession.utilities.Address;
import org.session.libsession.utilities.Contact;
import org.session.libsession.utilities.GroupUtil;
import org.session.libsession.utilities.IdentityKeyMismatch;
import org.session.libsession.utilities.IdentityKeyMismatchList;
import org.session.libsession.utilities.NetworkFailure;
import org.session.libsession.utilities.NetworkFailureList;
import org.session.libsession.utilities.TextSecurePreferences;
import org.session.libsession.utilities.Util;
import org.session.libsession.utilities.recipients.Recipient;
import org.session.libsession.utilities.recipients.RecipientFormattingException;
import org.session.libsignal.utilities.JsonUtil;
import org.session.libsignal.utilities.Log;
import org.session.libsignal.utilities.ThreadUtils;
import org.session.libsignal.utilities.guava.Optional;
import org.thoughtcrime.securesms.attachments.MmsNotificationAttachment;
import org.thoughtcrime.securesms.database.helpers.SQLCipherOpenHelper;
import org.thoughtcrime.securesms.database.model.MediaMmsMessageRecord;
import org.thoughtcrime.securesms.database.model.MessageRecord;
import org.thoughtcrime.securesms.database.model.MmsMessageRecord;
import org.thoughtcrime.securesms.database.model.NotificationMmsMessageRecord;
import org.thoughtcrime.securesms.database.model.Quote;
import org.thoughtcrime.securesms.mms.MmsException;
import org.thoughtcrime.securesms.mms.SlideDeck;

import java.io.Closeable;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class MmsDatabase extends MessagingDatabase {

  private static final String TAG = MmsDatabase.class.getSimpleName();

  public  static final String TABLE_NAME         = "mms";
          static final String DATE_SENT          = "date";
          static final String DATE_RECEIVED      = "date_received";
  public  static final String MESSAGE_BOX        = "msg_box";
          static final String CONTENT_LOCATION   = "ct_l";
          static final String EXPIRY             = "exp";
  public  static final String MESSAGE_TYPE       = "m_type";
          static final String MESSAGE_SIZE       = "m_size";
          static final String STATUS             = "st";
          static final String TRANSACTION_ID     = "tr_id";
          static final String PART_COUNT         = "part_count";
          static final String NETWORK_FAILURE    = "network_failures";

          static final String QUOTE_ID         = "quote_id";
          static final String QUOTE_AUTHOR     = "quote_author";
          static final String QUOTE_BODY       = "quote_body";
          static final String QUOTE_ATTACHMENT = "quote_attachment";
          static final String QUOTE_MISSING    = "quote_missing";

          static final String SHARED_CONTACTS = "shared_contacts";
          static final String LINK_PREVIEWS   = "previews";

  public static final String CREATE_TABLE = "CREATE TABLE " + TABLE_NAME + " (" + ID + " INTEGER PRIMARY KEY, "                          +
    THREAD_ID + " INTEGER, " + DATE_SENT + " INTEGER, " + DATE_RECEIVED + " INTEGER, " + MESSAGE_BOX + " INTEGER, " +
    READ + " INTEGER DEFAULT 0, " + "m_id" + " TEXT, " + "sub" + " TEXT, "                +
    "sub_cs" + " INTEGER, " + BODY + " TEXT, " + PART_COUNT + " INTEGER, "               +
    "ct_t" + " TEXT, " + CONTENT_LOCATION + " TEXT, " + ADDRESS + " TEXT, "               +
    ADDRESS_DEVICE_ID + " INTEGER, "                                                            +
    EXPIRY + " INTEGER, " + "m_cls" + " TEXT, " + MESSAGE_TYPE + " INTEGER, "             +
    "v" + " INTEGER, " + MESSAGE_SIZE + " INTEGER, " + "pri" + " INTEGER, "          +
    "rr" + " INTEGER, " + "rpt_a" + " INTEGER, " + "resp_st" + " INTEGER, " +
    STATUS + " INTEGER, " + TRANSACTION_ID + " TEXT, " + "retr_st" + " INTEGER, "         +
    "retr_txt" + " TEXT, " + "retr_txt_cs" + " INTEGER, " + "read_status" + " INTEGER, "    +
    "ct_cls" + " INTEGER, " + "resp_txt" + " TEXT, " + "d_tm" + " INTEGER, "     +
    DELIVERY_RECEIPT_COUNT + " INTEGER DEFAULT 0, " + MISMATCHED_IDENTITIES + " TEXT DEFAULT NULL, "     +
    NETWORK_FAILURE + " TEXT DEFAULT NULL," + "d_rpt" + " INTEGER, " +
    SUBSCRIPTION_ID + " INTEGER DEFAULT -1, " + EXPIRES_IN + " INTEGER DEFAULT 0, " +
    EXPIRE_STARTED + " INTEGER DEFAULT 0, " + NOTIFIED + " INTEGER DEFAULT 0, " +
    READ_RECEIPT_COUNT + " INTEGER DEFAULT 0, " + QUOTE_ID + " INTEGER DEFAULT 0, " +
    QUOTE_AUTHOR + " TEXT, " + QUOTE_BODY + " TEXT, " + QUOTE_ATTACHMENT + " INTEGER DEFAULT -1, " +
    QUOTE_MISSING + " INTEGER DEFAULT 0, " + SHARED_CONTACTS + " TEXT, " + UNIDENTIFIED + " INTEGER DEFAULT 0, " +
    LINK_PREVIEWS + " TEXT);";

  public static final String[] CREATE_INDEXS = {
    "CREATE INDEX IF NOT EXISTS mms_thread_id_index ON " + TABLE_NAME + " (" + THREAD_ID + ");",
    "CREATE INDEX IF NOT EXISTS mms_read_index ON " + TABLE_NAME + " (" + READ + ");",
    "CREATE INDEX IF NOT EXISTS mms_read_and_notified_and_thread_id_index ON " + TABLE_NAME + "(" + READ + "," + NOTIFIED + "," + THREAD_ID + ");",
    "CREATE INDEX IF NOT EXISTS mms_message_box_index ON " + TABLE_NAME + " (" + MESSAGE_BOX + ");",
    "CREATE INDEX IF NOT EXISTS mms_date_sent_index ON " + TABLE_NAME + " (" + DATE_SENT + ");",
    "CREATE INDEX IF NOT EXISTS mms_thread_date_index ON " + TABLE_NAME + " (" + THREAD_ID + ", " + DATE_RECEIVED + ");",
  };

  private static final String[] MMS_PROJECTION = new String[] {
      MmsDatabase.TABLE_NAME + "." + ID + " AS " + ID,
      THREAD_ID, DATE_SENT + " AS " + NORMALIZED_DATE_SENT,
      DATE_RECEIVED + " AS " + NORMALIZED_DATE_RECEIVED,
      MESSAGE_BOX, READ,
      CONTENT_LOCATION, EXPIRY, MESSAGE_TYPE,
      MESSAGE_SIZE, STATUS, TRANSACTION_ID,
      BODY, PART_COUNT, ADDRESS, ADDRESS_DEVICE_ID,
      DELIVERY_RECEIPT_COUNT, READ_RECEIPT_COUNT, MISMATCHED_IDENTITIES, NETWORK_FAILURE, SUBSCRIPTION_ID,
      EXPIRES_IN, EXPIRE_STARTED, NOTIFIED, QUOTE_ID, QUOTE_AUTHOR, QUOTE_BODY, QUOTE_ATTACHMENT, QUOTE_MISSING,
      SHARED_CONTACTS, LINK_PREVIEWS, UNIDENTIFIED,
      "json_group_array(json_object(" +
          "'" + AttachmentDatabase.ROW_ID + "', " + AttachmentDatabase.TABLE_NAME + "." + AttachmentDatabase.ROW_ID + ", " +
          "'" + AttachmentDatabase.UNIQUE_ID + "', " + AttachmentDatabase.TABLE_NAME + "." + AttachmentDatabase.UNIQUE_ID + ", " +
          "'" + AttachmentDatabase.MMS_ID + "', " + AttachmentDatabase.TABLE_NAME + "." + AttachmentDatabase.MMS_ID + ", " +
          "'" + AttachmentDatabase.SIZE + "', " + AttachmentDatabase.TABLE_NAME + "." + AttachmentDatabase.SIZE + ", " +
          "'" + AttachmentDatabase.FILE_NAME + "', " + AttachmentDatabase.TABLE_NAME + "." + AttachmentDatabase.FILE_NAME + ", " +
          "'" + AttachmentDatabase.DATA + "', " + AttachmentDatabase.TABLE_NAME + "." + AttachmentDatabase.DATA + ", " +
          "'" + AttachmentDatabase.THUMBNAIL + "', " + AttachmentDatabase.TABLE_NAME + "." + AttachmentDatabase.THUMBNAIL + ", " +
          "'" + AttachmentDatabase.CONTENT_TYPE + "', " + AttachmentDatabase.TABLE_NAME + "." + AttachmentDatabase.CONTENT_TYPE + ", " +
          "'" + AttachmentDatabase.CONTENT_LOCATION + "', " + AttachmentDatabase.TABLE_NAME + "." + AttachmentDatabase.CONTENT_LOCATION + ", " +
          "'" + AttachmentDatabase.FAST_PREFLIGHT_ID + "', " + AttachmentDatabase.TABLE_NAME + "." + AttachmentDatabase.FAST_PREFLIGHT_ID + "," +
          "'" + AttachmentDatabase.VOICE_NOTE + "', " + AttachmentDatabase.TABLE_NAME + "." + AttachmentDatabase.VOICE_NOTE + "," +
          "'" + AttachmentDatabase.WIDTH + "', " + AttachmentDatabase.TABLE_NAME + "." + AttachmentDatabase.WIDTH + "," +
          "'" + AttachmentDatabase.HEIGHT + "', " + AttachmentDatabase.TABLE_NAME + "." + AttachmentDatabase.HEIGHT + "," +
          "'" + AttachmentDatabase.QUOTE + "', " + AttachmentDatabase.TABLE_NAME + "." + AttachmentDatabase.QUOTE + ", " +
          "'" + AttachmentDatabase.CONTENT_DISPOSITION + "', " + AttachmentDatabase.TABLE_NAME + "." + AttachmentDatabase.CONTENT_DISPOSITION + ", " +
          "'" + AttachmentDatabase.NAME + "', " + AttachmentDatabase.TABLE_NAME + "." + AttachmentDatabase.NAME + ", " +
          "'" + AttachmentDatabase.TRANSFER_STATE + "', " + AttachmentDatabase.TABLE_NAME + "." + AttachmentDatabase.TRANSFER_STATE + ", " +
          "'" + AttachmentDatabase.CAPTION + "', " + AttachmentDatabase.TABLE_NAME + "." + AttachmentDatabase.CAPTION + ", " +
          "'" + AttachmentDatabase.STICKER_PACK_ID + "', " + AttachmentDatabase.TABLE_NAME + "." + AttachmentDatabase.STICKER_PACK_ID+ ", " +
          "'" + AttachmentDatabase.STICKER_PACK_KEY + "', " + AttachmentDatabase.TABLE_NAME + "." + AttachmentDatabase.STICKER_PACK_KEY + ", " +
          "'" + AttachmentDatabase.STICKER_ID + "', " + AttachmentDatabase.TABLE_NAME + "." + AttachmentDatabase.STICKER_ID +
          ")) AS " + AttachmentDatabase.ATTACHMENT_JSON_ALIAS,
  };

  private static final String RAW_ID_WHERE = TABLE_NAME + "._id = ?";

  private final EarlyReceiptCache earlyDeliveryReceiptCache = new EarlyReceiptCache();
  private final EarlyReceiptCache earlyReadReceiptCache     = new EarlyReceiptCache();

  public MmsDatabase(Context context, SQLCipherOpenHelper databaseHelper) {
    super(context, databaseHelper);
  }

  @Override
  protected String getTableName() {
    return TABLE_NAME;
  }

  public int getMessageCountForThread(long threadId) {
    SQLiteDatabase db = databaseHelper.getReadableDatabase();
    Cursor cursor     = null;

    try {
      cursor = db.query(TABLE_NAME, new String[] {"COUNT(*)"}, THREAD_ID + " = ?", new String[] {threadId+""}, null, null, null);

      if (cursor != null && cursor.moveToFirst())
        return cursor.getInt(0);
    } finally {
      if (cursor != null)
        cursor.close();
    }

    return 0;
  }

  public void addFailures(long messageId, List<NetworkFailure> failure) {
    try {
      addToDocument(messageId, NETWORK_FAILURE, failure, NetworkFailureList.class);
    } catch (IOException e) {
      Log.w(TAG, e);
    }
  }

  public void removeFailure(long messageId, NetworkFailure failure) {
    try {
      removeFromDocument(messageId, NETWORK_FAILURE, failure, NetworkFailureList.class);
    } catch (IOException e) {
      Log.w(TAG, e);
    }
  }

  public boolean isOutgoingMessage(long timestamp) {
    SQLiteDatabase database   = databaseHelper.getWritableDatabase();
    Cursor         cursor     = null;
    boolean        isOutgoing = false;

    try {
      cursor = database.query(TABLE_NAME, new String[] { ID, THREAD_ID, MESSAGE_BOX, ADDRESS }, DATE_SENT + " = ?", new String[] { String.valueOf(timestamp) }, null, null, null, null);

      while (cursor.moveToNext()) {
        if (Types.isOutgoingMessageType(cursor.getLong(cursor.getColumnIndexOrThrow(MESSAGE_BOX)))) {
          isOutgoing = true;
        }
      }
    } finally {
      if (cursor != null)
        cursor.close();
    }
    return isOutgoing;
  }

  public void incrementReceiptCount(SyncMessageId messageId, long timestamp, boolean deliveryReceipt, boolean readReceipt) {
    SQLiteDatabase database = databaseHelper.getWritableDatabase();
    Cursor         cursor   = null;
    boolean        found    = false;

    try {
      cursor = database.query(TABLE_NAME, new String[] {ID, THREAD_ID, MESSAGE_BOX, ADDRESS}, DATE_SENT + " = ?", new String[] {String.valueOf(messageId.getTimetamp())}, null, null, null, null);

      while (cursor.moveToNext()) {
        if (Types.isOutgoingMessageType(cursor.getLong(cursor.getColumnIndexOrThrow(MESSAGE_BOX)))) {
          Address theirAddress = Address.fromSerialized(cursor.getString(cursor.getColumnIndexOrThrow(ADDRESS)));
          Address ourAddress   = messageId.getAddress();
          String  columnName   = deliveryReceipt ? DELIVERY_RECEIPT_COUNT : READ_RECEIPT_COUNT;

          if (ourAddress.equals(theirAddress) || theirAddress.isGroup()) {
            long id       = cursor.getLong(cursor.getColumnIndexOrThrow(ID));
            long threadId = cursor.getLong(cursor.getColumnIndexOrThrow(THREAD_ID));
            int  status   = deliveryReceipt ? GroupReceiptDatabase.STATUS_DELIVERED : GroupReceiptDatabase.STATUS_READ;

            found = true;

            database.execSQL("UPDATE " + TABLE_NAME + " SET " +
                             columnName + " = " + columnName + " + 1 WHERE " + ID + " = ?",
                             new String[] {String.valueOf(id)});

            DatabaseFactory.getGroupReceiptDatabase(context).update(ourAddress, id, status, timestamp);
            DatabaseFactory.getThreadDatabase(context).update(threadId, false);
            notifyConversationListeners(threadId);
          }
        }
      }

      if (!found) {
        if (deliveryReceipt) earlyDeliveryReceiptCache.increment(messageId.getTimetamp(), messageId.getAddress());
        if (readReceipt)     earlyReadReceiptCache.increment(messageId.getTimetamp(), messageId.getAddress());
      }
    } finally {
      if (cursor != null)
        cursor.close();
    }
  }

  public void updateSentTimestamp(long messageId, long newTimestamp, long threadId) {
    SQLiteDatabase db = databaseHelper.getWritableDatabase();
    db.execSQL("UPDATE " + TABLE_NAME + " SET " + DATE_SENT + " = ? " +
                    "WHERE " + ID + " = ?",
            new String[] {newTimestamp + "", messageId + ""});
    notifyConversationListeners(threadId);
    notifyConversationListListeners();
  }

  public long getThreadIdForMessage(long id) {
    String sql        = "SELECT " + THREAD_ID + " FROM " + TABLE_NAME + " WHERE " + ID + " = ?";
    String[] sqlArgs  = new String[] {id+""};
    SQLiteDatabase db = databaseHelper.getReadableDatabase();

    Cursor cursor = null;

    try {
      cursor = db.rawQuery(sql, sqlArgs);
      if (cursor != null && cursor.moveToFirst())
        return cursor.getLong(0);
      else
        return -1;
    } finally {
      if (cursor != null)
        cursor.close();
    }
  }

  private long getThreadIdFor(IncomingMediaMessage retrieved) throws RecipientFormattingException, MmsException {
    if (retrieved.getGroupId() != null) {
      Recipient groupRecipients = Recipient.from(context, retrieved.getGroupId(), true);
      return DatabaseFactory.getThreadDatabase(context).getOrCreateThreadIdFor(groupRecipients);
    } else {
      Recipient sender = Recipient.from(context, retrieved.getFrom(), true);
      return DatabaseFactory.getThreadDatabase(context).getOrCreateThreadIdFor(sender);
    }
  }

  private long getThreadIdFor(@NonNull NotificationInd notification) {
    String fromString = notification.getFrom() != null && notification.getFrom().getTextString() != null
                      ? Util.toIsoString(notification.getFrom().getTextString())
                      : "";
    Recipient recipient = Recipient.from(context, Address.fromExternal(context, fromString), false);
    return DatabaseFactory.getThreadDatabase(context).getOrCreateThreadIdFor(recipient);
  }

  private Cursor rawQuery(@NonNull String where, @Nullable String[] arguments) {
    SQLiteDatabase database = databaseHelper.getReadableDatabase();
    return database.rawQuery("SELECT " + Util.join(MMS_PROJECTION, ",") +
                             " FROM " + MmsDatabase.TABLE_NAME +  " LEFT OUTER JOIN " + AttachmentDatabase.TABLE_NAME +
                             " ON (" + MmsDatabase.TABLE_NAME + "." + MmsDatabase.ID + " = " + AttachmentDatabase.TABLE_NAME + "." + AttachmentDatabase.MMS_ID + ")" +
                             " WHERE " + where + " GROUP BY " + MmsDatabase.TABLE_NAME + "." + MmsDatabase.ID, arguments);
  }

  public Cursor getMessage(long messageId) {
    Cursor cursor = rawQuery(RAW_ID_WHERE, new String[] {messageId + ""});
    setNotifyConverationListeners(cursor, getThreadIdForMessage(messageId));
    return cursor;
  }

  public Reader getExpireStartedMessages() {
    String where = EXPIRE_STARTED + " > 0";
    return readerFor(rawQuery(where, null));
  }

  private void updateMailboxBitmask(long id, long maskOff, long maskOn, Optional<Long> threadId) {
    SQLiteDatabase db = databaseHelper.getWritableDatabase();
    db.execSQL("UPDATE " + TABLE_NAME +
                   " SET " + MESSAGE_BOX + " = (" + MESSAGE_BOX + " & " + (Types.TOTAL_MASK - maskOff) + " | " + maskOn + " )" +
                   " WHERE " + ID + " = ?", new String[] {id + ""});

    if (threadId.isPresent()) {
      DatabaseFactory.getThreadDatabase(context).update(threadId.get(), false);
    }
  }

  public void markAsPendingInsecureSmsFallback(long messageId) {
    long threadId = getThreadIdForMessage(messageId);
    updateMailboxBitmask(messageId, Types.BASE_TYPE_MASK, Types.BASE_PENDING_INSECURE_SMS_FALLBACK, Optional.of(threadId));
    notifyConversationListeners(threadId);
  }

  public void markAsSending(long messageId) {
    long threadId = getThreadIdForMessage(messageId);
    updateMailboxBitmask(messageId, Types.BASE_TYPE_MASK, Types.BASE_SENDING_TYPE, Optional.of(threadId));
    notifyConversationListeners(threadId);
  }

  public void markAsSentFailed(long messageId) {
    long threadId = getThreadIdForMessage(messageId);
    updateMailboxBitmask(messageId, Types.BASE_TYPE_MASK, Types.BASE_SENT_FAILED_TYPE, Optional.of(threadId));
    notifyConversationListeners(threadId);
  }

  @Override
  public void markAsSent(long messageId, boolean secure) {
    long threadId = getThreadIdForMessage(messageId);
    updateMailboxBitmask(messageId, Types.BASE_TYPE_MASK, Types.BASE_SENT_TYPE | (secure ? Types.PUSH_MESSAGE_BIT | Types.SECURE_MESSAGE_BIT : 0), Optional.of(threadId));
    notifyConversationListeners(threadId);
  }

  @Override
  public void markUnidentified(long messageId, boolean unidentified) {
    ContentValues contentValues = new ContentValues();
    contentValues.put(UNIDENTIFIED, unidentified ? 1 : 0);

    SQLiteDatabase db = databaseHelper.getWritableDatabase();
    db.update(TABLE_NAME, contentValues, ID_WHERE, new String[] {String.valueOf(messageId)});
  }

  @Override
  public void markAsDeleted(long messageId, boolean read) {
    SQLiteDatabase database     = databaseHelper.getWritableDatabase();
    ContentValues contentValues = new ContentValues();
    contentValues.put(READ, 1);
    contentValues.put(BODY, "");
    database.update(TABLE_NAME, contentValues, ID_WHERE, new String[] {String.valueOf(messageId)});

    AttachmentDatabase attachmentDatabase = DatabaseFactory.getAttachmentDatabase(context);
    ThreadUtils.queue(() -> attachmentDatabase.deleteAttachmentsForMessage(messageId));

    long threadId = getThreadIdForMessage(messageId);
    if (!read) { DatabaseFactory.getThreadDatabase(context).decrementUnread(threadId, 1); }
    updateMailboxBitmask(messageId, Types.BASE_TYPE_MASK, Types.BASE_DELETED_TYPE, Optional.of(threadId));
    notifyConversationListeners(threadId);
  }

  @Override
  public void markExpireStarted(long messageId) {
    markExpireStarted(messageId, System.currentTimeMillis());
  }

  @Override
  public void markExpireStarted(long messageId, long startedTimestamp) {
    ContentValues contentValues = new ContentValues();
    contentValues.put(EXPIRE_STARTED, startedTimestamp);

    SQLiteDatabase db = databaseHelper.getWritableDatabase();
    db.update(TABLE_NAME, contentValues, ID_WHERE, new String[] {String.valueOf(messageId)});

    long threadId = getThreadIdForMessage(messageId);
    notifyConversationListeners(threadId);
  }

  public void markAsNotified(long id) {
    SQLiteDatabase database      = databaseHelper.getWritableDatabase();
    ContentValues  contentValues = new ContentValues();

    contentValues.put(NOTIFIED, 1);

    database.update(TABLE_NAME, contentValues, ID_WHERE, new String[] {String.valueOf(id)});
  }


  public List<MarkedMessageInfo> setMessagesRead(long threadId) {
    return setMessagesRead(THREAD_ID + " = ? AND " + READ + " = 0", new String[] {String.valueOf(threadId)});
  }

  public List<MarkedMessageInfo> setAllMessagesRead() {
    return setMessagesRead(READ + " = 0", null);
  }

  private List<MarkedMessageInfo> setMessagesRead(String where, String[] arguments) {
    SQLiteDatabase          database = databaseHelper.getWritableDatabase();
    List<MarkedMessageInfo> result   = new LinkedList<>();
    Cursor                  cursor   = null;

    database.beginTransaction();

    try {
      cursor = database.query(TABLE_NAME, new String[] {ID, ADDRESS, DATE_SENT, MESSAGE_BOX, EXPIRES_IN, EXPIRE_STARTED}, where, arguments, null, null, null);

      while(cursor != null && cursor.moveToNext()) {
        if (Types.isSecureType(cursor.getLong(3))) {
          SyncMessageId  syncMessageId  = new SyncMessageId(Address.fromSerialized(cursor.getString(1)), cursor.getLong(2));
          ExpirationInfo expirationInfo = new ExpirationInfo(cursor.getLong(0), cursor.getLong(4), cursor.getLong(5), true);

          result.add(new MarkedMessageInfo(syncMessageId, expirationInfo));
        }
      }

      ContentValues contentValues = new ContentValues();
      contentValues.put(READ, 1);

      database.update(TABLE_NAME, contentValues, where, arguments);
      database.setTransactionSuccessful();
    } finally {
      if (cursor != null) cursor.close();
      database.endTransaction();
    }

    return result;
  }

  public OutgoingMediaMessage getOutgoingMessage(long messageId)
      throws MmsException, NoSuchMessageException
  {
    AttachmentDatabase attachmentDatabase = DatabaseFactory.getAttachmentDatabase(context);
    Cursor             cursor             = null;

    try {
      cursor = rawQuery(RAW_ID_WHERE, new String[] {String.valueOf(messageId)});

      if (cursor != null && cursor.moveToNext()) {
        List<DatabaseAttachment> associatedAttachments = attachmentDatabase.getAttachmentsForMessage(messageId);

        long             outboxType         = cursor.getLong(cursor.getColumnIndexOrThrow(MESSAGE_BOX));
        String           body               = cursor.getString(cursor.getColumnIndexOrThrow(BODY));
        long             timestamp          = cursor.getLong(cursor.getColumnIndexOrThrow(NORMALIZED_DATE_SENT));
        int              subscriptionId     = cursor.getInt(cursor.getColumnIndexOrThrow(SUBSCRIPTION_ID));
        long             expiresIn          = cursor.getLong(cursor.getColumnIndexOrThrow(EXPIRES_IN));
        String           address            = cursor.getString(cursor.getColumnIndexOrThrow(ADDRESS));
        long             threadId           = cursor.getLong(cursor.getColumnIndexOrThrow(THREAD_ID));
        int              distributionType   = DatabaseFactory.getThreadDatabase(context).getDistributionType(threadId);
        String           mismatchDocument   = cursor.getString(cursor.getColumnIndexOrThrow(MmsDatabase.MISMATCHED_IDENTITIES));
        String           networkDocument    = cursor.getString(cursor.getColumnIndexOrThrow(MmsDatabase.NETWORK_FAILURE));

        long              quoteId            = cursor.getLong(cursor.getColumnIndexOrThrow(QUOTE_ID));
        String            quoteAuthor        = cursor.getString(cursor.getColumnIndexOrThrow(QUOTE_AUTHOR));
        String            quoteText          = cursor.getString(cursor.getColumnIndexOrThrow(QUOTE_BODY));
        boolean           quoteMissing       = cursor.getInt(cursor.getColumnIndexOrThrow(QUOTE_MISSING)) == 1;
        List<Attachment>  quoteAttachments   = Stream.of(associatedAttachments).filter(Attachment::isQuote).map(a -> (Attachment)a).toList();
        List<Contact>     contacts           = getSharedContacts(cursor, associatedAttachments);
        Set<Attachment>   contactAttachments = new HashSet<>(Stream.of(contacts).map(Contact::getAvatarAttachment).filter(a -> a != null).toList());
        List<LinkPreview> previews           = getLinkPreviews(cursor, associatedAttachments);
        Set<Attachment>   previewAttachments = Stream.of(previews).filter(lp -> lp.getThumbnail().isPresent()).map(lp -> lp.getThumbnail().get()).collect(Collectors.toSet());
        List<Attachment>  attachments        = Stream.of(associatedAttachments).filterNot(Attachment::isQuote)
                                                                               .filterNot(contactAttachments::contains)
                                                                               .filterNot(previewAttachments::contains)
                                                                               .map(a -> (Attachment)a).toList();

        Recipient                 recipient       = Recipient.from(context, Address.fromSerialized(address), false);
        List<NetworkFailure>      networkFailures = new LinkedList<>();
        List<IdentityKeyMismatch> mismatches      = new LinkedList<>();
        QuoteModel                quote           = null;

        if (quoteId > 0 && (!TextUtils.isEmpty(quoteText) || !quoteAttachments.isEmpty())) {
          quote = new QuoteModel(quoteId, Address.fromSerialized(quoteAuthor), quoteText, quoteMissing, quoteAttachments);
        }

        if (!TextUtils.isEmpty(mismatchDocument)) {
          try {
            mismatches = JsonUtil.fromJson(mismatchDocument, IdentityKeyMismatchList.class).getList();
          } catch (IOException e) {
            Log.w(TAG, e);
          }
        }

        if (!TextUtils.isEmpty(networkDocument)) {
          try {
            networkFailures = JsonUtil.fromJson(networkDocument, NetworkFailureList.class).getList();
          } catch (IOException e) {
            Log.w(TAG, e);
          }
        }

        OutgoingMediaMessage message = new OutgoingMediaMessage(recipient, body, attachments, timestamp, subscriptionId, expiresIn, distributionType, quote, contacts, previews, networkFailures, mismatches);

        if (Types.isSecureType(outboxType)) {
          return new OutgoingSecureMediaMessage(message);
        }

        return message;
      }

      throw new NoSuchMessageException("No record found for id: " + messageId);
    } finally {
      if (cursor != null)
        cursor.close();
    }
  }

  private List<Contact> getSharedContacts(@NonNull Cursor cursor, @NonNull List<DatabaseAttachment> attachments) {
    String serializedContacts = cursor.getString(cursor.getColumnIndexOrThrow(SHARED_CONTACTS));

    if (TextUtils.isEmpty(serializedContacts)) {
      return Collections.emptyList();
    }

    Map<AttachmentId, DatabaseAttachment> attachmentIdMap = new HashMap<>();
    for (DatabaseAttachment attachment : attachments) {
      attachmentIdMap.put(attachment.getAttachmentId(), attachment);
    }

    try {
      List<Contact> contacts     = new LinkedList<>();
      JSONArray     jsonContacts = new JSONArray(serializedContacts);

      for (int i = 0; i < jsonContacts.length(); i++) {
        Contact contact = Contact.deserialize(jsonContacts.getJSONObject(i).toString());

        if (contact.getAvatar() != null && contact.getAvatar().getAttachmentId() != null) {
          DatabaseAttachment attachment    = attachmentIdMap.get(contact.getAvatar().getAttachmentId());
          Contact.Avatar     updatedAvatar = new Contact.Avatar(contact.getAvatar().getAttachmentId(),
                                                                attachment,
                                                                contact.getAvatar().isProfile());
          contacts.add(new Contact(contact, updatedAvatar));
        } else {
          contacts.add(contact);
        }
      }

      return contacts;
    } catch (JSONException | IOException e) {
      Log.w(TAG, "Failed to parse shared contacts.", e);
    }

    return Collections.emptyList();
  }

  private List<LinkPreview> getLinkPreviews(@NonNull Cursor cursor, @NonNull List<DatabaseAttachment> attachments) {
    String serializedPreviews = cursor.getString(cursor.getColumnIndexOrThrow(LINK_PREVIEWS));

    if (TextUtils.isEmpty(serializedPreviews)) {
      return Collections.emptyList();
    }

    Map<AttachmentId, DatabaseAttachment> attachmentIdMap = new HashMap<>();
    for (DatabaseAttachment attachment : attachments) {
      attachmentIdMap.put(attachment.getAttachmentId(), attachment);
    }

    try {
      List<LinkPreview> previews     = new LinkedList<>();
      JSONArray         jsonPreviews = new JSONArray(serializedPreviews);

      for (int i = 0; i < jsonPreviews.length(); i++) {
        LinkPreview preview = LinkPreview.deserialize(jsonPreviews.getJSONObject(i).toString());

        if (preview.getAttachmentId() != null) {
          DatabaseAttachment attachment = attachmentIdMap.get(preview.getAttachmentId());
          if (attachment != null) {
            previews.add(new LinkPreview(preview.getUrl(), preview.getTitle(), attachment));
          }
        } else {
          previews.add(preview);
        }
      }

      return previews;
    } catch (JSONException | IOException e) {
      Log.w(TAG, "Failed to parse shared contacts.", e);
    }

    return Collections.emptyList();
  }

  private Optional<InsertResult> insertMessageInbox(IncomingMediaMessage retrieved,
                                                    String contentLocation,
                                                    long threadId, long mailbox,
                                                    long serverTimestamp)
      throws MmsException
  {
    if (threadId == -1 || retrieved.isGroupMessage()) {
      try {
        threadId = getThreadIdFor(retrieved);
      } catch (RecipientFormattingException e) {
        Log.w("MmsDatabase", e);
        if (threadId == -1)
          throw new MmsException(e);
      }
    }

    ContentValues contentValues = new ContentValues();

    contentValues.put(DATE_SENT, retrieved.getSentTimeMillis());
    contentValues.put(ADDRESS, retrieved.getFrom().serialize());

    contentValues.put(MESSAGE_BOX, mailbox);
    contentValues.put(MESSAGE_TYPE, PduHeaders.MESSAGE_TYPE_RETRIEVE_CONF);
    contentValues.put(THREAD_ID, threadId);
    contentValues.put(CONTENT_LOCATION, contentLocation);
    contentValues.put(STATUS, Status.DOWNLOAD_INITIALIZED);
    // In open groups messages should be sorted by their server timestamp
    long receivedTimestamp = serverTimestamp;
    if (serverTimestamp == 0) { receivedTimestamp = retrieved.getSentTimeMillis(); }
    contentValues.put(DATE_RECEIVED, receivedTimestamp); // Loki - This is important due to how we handle GIFs
    contentValues.put(PART_COUNT, retrieved.getAttachments().size());
    contentValues.put(SUBSCRIPTION_ID, retrieved.getSubscriptionId());
    contentValues.put(EXPIRES_IN, retrieved.getExpiresIn());
    contentValues.put(READ, retrieved.isExpirationUpdate() ? 1 : 0);
    contentValues.put(UNIDENTIFIED, retrieved.isUnidentified());

    if (!contentValues.containsKey(DATE_SENT)) {
      contentValues.put(DATE_SENT, contentValues.getAsLong(DATE_RECEIVED));
    }

    List<Attachment> quoteAttachments = new LinkedList<>();

    if (retrieved.getQuote() != null) {
      contentValues.put(QUOTE_ID, retrieved.getQuote().getId());
      contentValues.put(QUOTE_BODY, retrieved.getQuote().getText());
      contentValues.put(QUOTE_AUTHOR, retrieved.getQuote().getAuthor().serialize());
      contentValues.put(QUOTE_MISSING, retrieved.getQuote().getMissing() ? 1 : 0);

      quoteAttachments = retrieved.getQuote().getAttachments();
    }

    if (retrieved.isPushMessage() && isDuplicate(retrieved, threadId)) {
      Log.w(TAG, "Ignoring duplicate media message (" + retrieved.getSentTimeMillis() + ")");
      return Optional.absent();
    }

    long messageId = insertMediaMessage(retrieved.getBody(), retrieved.getAttachments(), quoteAttachments, retrieved.getSharedContacts(), retrieved.getLinkPreviews(), contentValues, null);

    if (!Types.isExpirationTimerUpdate(mailbox)) {
      DatabaseFactory.getThreadDatabase(context).incrementUnread(threadId, 1);
      DatabaseFactory.getThreadDatabase(context).update(threadId, true);
    }

    notifyConversationListeners(threadId);

    return Optional.of(new InsertResult(messageId, threadId));
  }

  public Optional<InsertResult> insertSecureDecryptedMessageOutbox(OutgoingMediaMessage retrieved, long threadId, long serverTimestamp)
          throws MmsException
  {
    if (threadId == -1) {
      if(retrieved.isGroup()) {
        String decodedGroupId;
        if (retrieved instanceof OutgoingExpirationUpdateMessage) {
          decodedGroupId = ((OutgoingExpirationUpdateMessage)retrieved).getGroupId();
        } else {
          decodedGroupId = ((OutgoingGroupMediaMessage)retrieved).getGroupId();
        }
        String groupId;
        try {
          groupId = GroupUtil.doubleEncodeGroupID(decodedGroupId);
        } catch (IOException e) {
          Log.e(TAG, "Couldn't encrypt group ID");
          throw new MmsException(e);
        }
        Recipient group = Recipient.from(context, Address.fromSerialized(groupId), false);
        threadId = DatabaseFactory.getThreadDatabase(context).getOrCreateThreadIdFor(group);
      } else {
        threadId = DatabaseFactory.getThreadDatabase(context).getOrCreateThreadIdFor(retrieved.getRecipient());
      }
    }
    long messageId = insertMessageOutbox(retrieved, threadId, false, null, serverTimestamp);
    if (messageId == -1) {
      return Optional.absent();
    }
    markAsSent(messageId, true);
    return Optional.fromNullable(new InsertResult(messageId, threadId));
  }

  public Optional<InsertResult> insertSecureDecryptedMessageInbox(IncomingMediaMessage retrieved, long threadId, long serverTimestamp)
          throws MmsException
  {
    long type = Types.BASE_INBOX_TYPE | Types.SECURE_MESSAGE_BIT;

    if (retrieved.isPushMessage()) {
      type |= Types.PUSH_MESSAGE_BIT;
    }

    if (retrieved.isExpirationUpdate()) {
      type |= Types.EXPIRATION_TIMER_UPDATE_BIT;
    }

    if (retrieved.isScreenshotDataExtraction()) {
      type |= Types.SCREENSHOT_EXTRACTION_BIT;
    }

    if (retrieved.isMediaSavedDataExtraction()) {
      type |= Types.MEDIA_SAVED_EXTRACTION_BIT;
    }

    return insertMessageInbox(retrieved, "", threadId, type, serverTimestamp);
  }

  public Optional<InsertResult> insertSecureDecryptedMessageInbox(IncomingMediaMessage retrieved, long threadId)
          throws MmsException
  {
    return insertSecureDecryptedMessageInbox(retrieved, threadId, 0);
  }

  public long insertMessageOutbox(@NonNull OutgoingMediaMessage message,
                                  long threadId, boolean forceSms,
                                  @Nullable SmsDatabase.InsertListener insertListener)
          throws MmsException {
    return insertMessageOutbox(message, threadId, forceSms, insertListener, 0);
  }

  public long insertMessageOutbox(@NonNull OutgoingMediaMessage message,
                                  long threadId, boolean forceSms,
                                  @Nullable SmsDatabase.InsertListener insertListener,
                                  long serverTimestamp)
      throws MmsException
  {
    long type = Types.BASE_SENDING_TYPE;

    if (message.isSecure()) type |= (Types.SECURE_MESSAGE_BIT | Types.PUSH_MESSAGE_BIT);
    if (forceSms)           type |= Types.MESSAGE_FORCE_SMS_BIT;

    if (message.isGroup() && message instanceof OutgoingGroupMediaMessage) {
      if (((OutgoingGroupMediaMessage)message).isUpdateMessage()) type |= Types.GROUP_UPDATE_MESSAGE_BIT;
    }

    if (message.isExpirationUpdate()) {
      type |= Types.EXPIRATION_TIMER_UPDATE_BIT;
    }

    Map<Address, Long> earlyDeliveryReceipts = earlyDeliveryReceiptCache.remove(message.getSentTimeMillis());
    Map<Address, Long> earlyReadReceipts     = earlyReadReceiptCache.remove(message.getSentTimeMillis());

    ContentValues contentValues = new ContentValues();
    contentValues.put(DATE_SENT, message.getSentTimeMillis());
    contentValues.put(MESSAGE_TYPE, PduHeaders.MESSAGE_TYPE_SEND_REQ);

    contentValues.put(MESSAGE_BOX, type);
    contentValues.put(THREAD_ID, threadId);
    contentValues.put(READ, 1);
    // In open groups messages should be sorted by their server timestamp
    long receivedTimestamp = serverTimestamp;
    if (serverTimestamp == 0) { receivedTimestamp = System.currentTimeMillis(); }
    contentValues.put(DATE_RECEIVED, receivedTimestamp);
    contentValues.put(SUBSCRIPTION_ID, message.getSubscriptionId());
    contentValues.put(EXPIRES_IN, message.getExpiresIn());
    contentValues.put(ADDRESS, message.getRecipient().getAddress().serialize());
    contentValues.put(DELIVERY_RECEIPT_COUNT, Stream.of(earlyDeliveryReceipts.values()).mapToLong(Long::longValue).sum());
    contentValues.put(READ_RECEIPT_COUNT, Stream.of(earlyReadReceipts.values()).mapToLong(Long::longValue).sum());

    List<Attachment> quoteAttachments = new LinkedList<>();

    if (message.getOutgoingQuote() != null) {
      contentValues.put(QUOTE_ID, message.getOutgoingQuote().getId());
      contentValues.put(QUOTE_AUTHOR, message.getOutgoingQuote().getAuthor().serialize());
      contentValues.put(QUOTE_BODY, message.getOutgoingQuote().getText());
      contentValues.put(QUOTE_MISSING, message.getOutgoingQuote().getMissing() ? 1 : 0);

      quoteAttachments.addAll(message.getOutgoingQuote().getAttachments());
    }

    if (isDuplicate(message, threadId)) {
      Log.w(TAG, "Ignoring duplicate media message (" + message.getSentTimeMillis() + ")");
      return -1;
    }

    long messageId = insertMediaMessage(message.getBody(), message.getAttachments(), quoteAttachments, message.getSharedContacts(), message.getLinkPreviews(), contentValues, insertListener);

    if (message.getRecipient().getAddress().isGroup()) {
      List<Recipient>      members         = DatabaseFactory.getGroupDatabase(context).getGroupMembers(message.getRecipient().getAddress().toGroupString(), false);
      GroupReceiptDatabase receiptDatabase = DatabaseFactory.getGroupReceiptDatabase(context);

      receiptDatabase.insert(Stream.of(members).map(Recipient::getAddress).toList(),
                             messageId, GroupReceiptDatabase.STATUS_UNDELIVERED, message.getSentTimeMillis());

      for (Address address : earlyDeliveryReceipts.keySet()) receiptDatabase.update(address, messageId, GroupReceiptDatabase.STATUS_DELIVERED, -1);
      for (Address address : earlyReadReceipts.keySet())     receiptDatabase.update(address, messageId, GroupReceiptDatabase.STATUS_READ, -1);
    }

    DatabaseFactory.getThreadDatabase(context).setLastSeen(threadId);
    DatabaseFactory.getThreadDatabase(context).setHasSent(threadId, true);

    return messageId;
  }

  private long insertMediaMessage(@Nullable String body,
                                  @NonNull List<Attachment> attachments,
                                  @NonNull List<Attachment> quoteAttachments,
                                  @NonNull List<Contact> sharedContacts,
                                  @NonNull List<LinkPreview> linkPreviews,
                                  @NonNull ContentValues contentValues,
                                  @Nullable SmsDatabase.InsertListener insertListener)
      throws MmsException
  {
    SQLiteDatabase     db            = databaseHelper.getWritableDatabase();
    AttachmentDatabase partsDatabase = DatabaseFactory.getAttachmentDatabase(context);

    List<Attachment> allAttachments     = new LinkedList<>();
    List<Attachment> contactAttachments = Stream.of(sharedContacts).map(Contact::getAvatarAttachment).filter(a -> a != null).toList();
    List<Attachment> previewAttachments = Stream.of(linkPreviews).filter(lp -> lp.getThumbnail().isPresent()).map(lp -> lp.getThumbnail().get()).toList();

    allAttachments.addAll(attachments);
    allAttachments.addAll(contactAttachments);
    allAttachments.addAll(previewAttachments);

    contentValues.put(BODY, body);
    contentValues.put(PART_COUNT, allAttachments.size());

    db.beginTransaction();
    try {
      long messageId = db.insert(TABLE_NAME, null, contentValues);

      Map<Attachment, AttachmentId> insertedAttachments = partsDatabase.insertAttachmentsForMessage(messageId, allAttachments, quoteAttachments);
      String                        serializedContacts  = getSerializedSharedContacts(insertedAttachments, sharedContacts);
      String                        serializedPreviews  = getSerializedLinkPreviews(insertedAttachments, linkPreviews);

      if (!TextUtils.isEmpty(serializedContacts)) {
        ContentValues contactValues = new ContentValues();
        contactValues.put(SHARED_CONTACTS, serializedContacts);

        SQLiteDatabase database = databaseHelper.getReadableDatabase();
        int rows = database.update(TABLE_NAME, contactValues, ID + " = ?", new String[]{ String.valueOf(messageId) });

        if (rows <= 0) {
          Log.w(TAG, "Failed to update message with shared contact data.");
        }
      }

      if (!TextUtils.isEmpty(serializedPreviews)) {
        ContentValues contactValues = new ContentValues();
        contactValues.put(LINK_PREVIEWS, serializedPreviews);

        SQLiteDatabase database = databaseHelper.getReadableDatabase();
        int rows = database.update(TABLE_NAME, contactValues, ID + " = ?", new String[]{ String.valueOf(messageId) });

        if (rows <= 0) {
          Log.w(TAG, "Failed to update message with link preview data.");
        }
      }

      db.setTransactionSuccessful();
      return messageId;
    } finally {
      db.endTransaction();

      if (insertListener != null) {
        insertListener.onComplete();
      }

      notifyConversationListeners(contentValues.getAsLong(THREAD_ID));
      DatabaseFactory.getThreadDatabase(context).update(contentValues.getAsLong(THREAD_ID), true);
    }
  }

  public void deleteQuotedFromMessages(MessageRecord toDeleteRecord) {
    if (toDeleteRecord == null) { return; }
    String query = THREAD_ID + " = ?";
    Cursor threadMmsCursor = rawQuery(query, new String[]{String.valueOf(toDeleteRecord.getThreadId())});
    Reader reader = readerFor(threadMmsCursor);
    MmsMessageRecord messageRecord;

    while ((messageRecord = (MmsMessageRecord) reader.getNext()) != null) {
      if (messageRecord.getQuote() != null && toDeleteRecord.getDateSent() == messageRecord.getQuote().getId()) {
        setQuoteMissing(messageRecord.getId());
      }
    }
    reader.close();
  }

  @Override
  public boolean deleteMessage(long messageId) {
    long threadId = getThreadIdForMessage(messageId);
    AttachmentDatabase attachmentDatabase = DatabaseFactory.getAttachmentDatabase(context);
    ThreadUtils.queue(() -> attachmentDatabase.deleteAttachmentsForMessage(messageId));

    GroupReceiptDatabase groupReceiptDatabase = DatabaseFactory.getGroupReceiptDatabase(context);
    groupReceiptDatabase.deleteRowsForMessage(messageId);

    MessageRecord toDelete;
    try (Cursor messageCursor = getMessage(messageId)) {
      toDelete = readerFor(messageCursor).getNext();
    }

    deleteQuotedFromMessages(toDelete);
    SQLiteDatabase database = databaseHelper.getWritableDatabase();
    database.delete(TABLE_NAME, ID_WHERE, new String[] {messageId+""});
    boolean threadDeleted = DatabaseFactory.getThreadDatabase(context).update(threadId, false);
    notifyConversationListeners(threadId);
    notifyStickerListeners();
    notifyStickerPackListeners();
    return threadDeleted;
  }

  public void deleteThread(long threadId) {
    Set<Long> singleThreadSet = new HashSet<>();
    singleThreadSet.add(threadId);
    deleteThreads(singleThreadSet);
  }

  private @Nullable String getSerializedSharedContacts(@NonNull Map<Attachment, AttachmentId> insertedAttachmentIds, @NonNull List<Contact> contacts) {
    if (contacts.isEmpty()) return null;

    JSONArray sharedContactJson = new JSONArray();

    for (Contact contact : contacts) {
      try {
        AttachmentId attachmentId = null;

        if (contact.getAvatarAttachment() != null) {
          attachmentId = insertedAttachmentIds.get(contact.getAvatarAttachment());
        }

        Contact.Avatar updatedAvatar  = new Contact.Avatar(attachmentId, contact.getAvatarAttachment(), contact.getAvatar() != null && contact.getAvatar().isProfile());
        Contact updatedContact = new Contact(contact, updatedAvatar);

        sharedContactJson.put(new JSONObject(updatedContact.serialize()));
      } catch (JSONException | IOException e) {
        Log.w(TAG, "Failed to serialize shared contact. Skipping it.", e);
      }
    }
    return sharedContactJson.toString();
  }

  private @Nullable String getSerializedLinkPreviews(@NonNull Map<Attachment, AttachmentId> insertedAttachmentIds, @NonNull List<LinkPreview> previews) {
    if (previews.isEmpty()) return null;

    JSONArray linkPreviewJson = new JSONArray();

    for (LinkPreview preview : previews) {
      try {
        AttachmentId attachmentId = null;

        if (preview.getThumbnail().isPresent()) {
          attachmentId = insertedAttachmentIds.get(preview.getThumbnail().get());
        }

        LinkPreview updatedPreview = new LinkPreview(preview.getUrl(), preview.getTitle(), attachmentId);
        linkPreviewJson.put(new JSONObject(updatedPreview.serialize()));
      } catch (JSONException | IOException e) {
        Log.w(TAG, "Failed to serialize shared contact. Skipping it.", e);
      }
    }
    return linkPreviewJson.toString();
  }

  private boolean isDuplicate(IncomingMediaMessage message, long threadId) {
    SQLiteDatabase database = databaseHelper.getReadableDatabase();
    Cursor         cursor   = database.query(TABLE_NAME, null, DATE_SENT + " = ? AND " + ADDRESS + " = ? AND " + THREAD_ID + " = ?",
                                             new String[]{String.valueOf(message.getSentTimeMillis()), message.getFrom().serialize(), String.valueOf(threadId)},
                                             null, null, null, "1");

    try {
      return cursor != null && cursor.moveToFirst();
    } finally {
      if (cursor != null) cursor.close();
    }
  }

  private boolean isDuplicate(OutgoingMediaMessage message, long threadId) {
    SQLiteDatabase database = databaseHelper.getReadableDatabase();
    Cursor         cursor   = database.query(TABLE_NAME, null, DATE_SENT + " = ? AND " + ADDRESS + " = ? AND " + THREAD_ID + " = ?",
            new String[]{String.valueOf(message.getSentTimeMillis()), message.getRecipient().getAddress().serialize(), String.valueOf(threadId)},
            null, null, null, "1");

    try {
      return cursor != null && cursor.moveToFirst();
    } finally {
      if (cursor != null) cursor.close();
    }
  }

  public boolean isSent(long messageId) {
    SQLiteDatabase database = databaseHelper.getReadableDatabase();
    try (Cursor cursor = database.query(TABLE_NAME, new String[] {  MESSAGE_BOX }, ID + " = ?", new String[] { String.valueOf(messageId)}, null, null, null)) {
      if (cursor != null && cursor.moveToNext()) {
        long type = cursor.getLong(cursor.getColumnIndexOrThrow(MESSAGE_BOX));
        return Types.isSentType(type);
      }
    }
    return false;
  }

  /*package*/ void deleteThreads(Set<Long> threadIds) {
    SQLiteDatabase db = databaseHelper.getWritableDatabase();
    String where      = "";
    Cursor cursor     = null;

    for (long threadId : threadIds) {
      where += THREAD_ID + " = '" + threadId + "' OR ";
    }

    where = where.substring(0, where.length() - 4);

    try {
      cursor = db.query(TABLE_NAME, new String[] {ID}, where, null, null, null, null);

      while (cursor != null && cursor.moveToNext()) {
        deleteMessage(cursor.getLong(0));
      }

    } finally {
      if (cursor != null)
        cursor.close();
    }
  }

  /*package*/void deleteMessagesInThreadBeforeDate(long threadId, long date) {
    Cursor cursor = null;

    try {
      SQLiteDatabase db = databaseHelper.getReadableDatabase();
      String where      = THREAD_ID + " = ? AND (CASE (" + MESSAGE_BOX + " & " + Types.BASE_TYPE_MASK + ") ";

      for (long outgoingType : Types.OUTGOING_MESSAGE_TYPES) {
        where += " WHEN " + outgoingType + " THEN " + DATE_SENT + " < " + date;
      }

      where += (" ELSE " + DATE_RECEIVED + " < " + date + " END)");

      cursor = db.query(TABLE_NAME, new String[] {ID}, where, new String[] {threadId+""}, null, null, null);

      while (cursor != null && cursor.moveToNext()) {
        Log.i("MmsDatabase", "Trimming: " + cursor.getLong(0));
        deleteMessage(cursor.getLong(0));
      }

    } finally {
      if (cursor != null)
        cursor.close();
    }
  }


  public void deleteAllThreads() {
    DatabaseFactory.getAttachmentDatabase(context).deleteAllAttachments();
    DatabaseFactory.getGroupReceiptDatabase(context).deleteAllRows();

    SQLiteDatabase database = databaseHelper.getWritableDatabase();
    database.delete(TABLE_NAME, null, null);
  }

  public void beginTransaction() {
    databaseHelper.getWritableDatabase().beginTransaction();
  }

  public void setTransactionSuccessful() {
    databaseHelper.getWritableDatabase().setTransactionSuccessful();
  }

  public void endTransaction() {
    databaseHelper.getWritableDatabase().endTransaction();
  }

  public Reader readerFor(Cursor cursor) {
    return new Reader(cursor);
  }

  public OutgoingMessageReader readerFor(OutgoingMediaMessage message, long threadId) {
    return new OutgoingMessageReader(message, threadId);
  }

  public int setQuoteMissing(long messageId) {
    ContentValues contentValues = new ContentValues();
    contentValues.put(QUOTE_MISSING, 1);
    SQLiteDatabase database = databaseHelper.getWritableDatabase();
    int rows = database.update(TABLE_NAME, contentValues, ID + " = ?", new String[]{ String.valueOf(messageId) });
    return rows;
  }

  public static class Status {
    public static final int DOWNLOAD_INITIALIZED     = 1;
    public static final int DOWNLOAD_NO_CONNECTIVITY = 2;
    public static final int DOWNLOAD_CONNECTING      = 3;
  }

  public class OutgoingMessageReader {

    private final OutgoingMediaMessage message;
    private final long                 id;
    private final long                 threadId;

    public OutgoingMessageReader(OutgoingMediaMessage message, long threadId) {
      this.message  = message;
      this.id       = new SecureRandom().nextLong();
      this.threadId = threadId;
    }

    public MessageRecord getCurrent() {
      SlideDeck slideDeck = new SlideDeck(context, message.getAttachments());

      return new MediaMmsMessageRecord(id, message.getRecipient(), message.getRecipient(),
                                       1, System.currentTimeMillis(), System.currentTimeMillis(),
                                       0, threadId, message.getBody(),
                                       slideDeck, slideDeck.getSlides().size(),
                                       message.isSecure() ? MmsSmsColumns.Types.getOutgoingEncryptedMessageType() : MmsSmsColumns.Types.getOutgoingSmsMessageType(),
                                       new LinkedList<IdentityKeyMismatch>(),
                                       new LinkedList<NetworkFailure>(),
                                       message.getSubscriptionId(),
                                       message.getExpiresIn(),
                                       System.currentTimeMillis(), 0,
                                       message.getOutgoingQuote() != null ?
                                           new Quote(message.getOutgoingQuote().getId(),
                                                     message.getOutgoingQuote().getAuthor(),
                                                     message.getOutgoingQuote().getText(),
                                                     message.getOutgoingQuote().getMissing(),
                                                     new SlideDeck(context, message.getOutgoingQuote().getAttachments())) :
                                           null,
                                       message.getSharedContacts(), message.getLinkPreviews(), false);
    }
  }

  public class Reader implements Closeable {

    private final Cursor cursor;

    public Reader(Cursor cursor) {
      this.cursor = cursor;
    }

    public MessageRecord getNext() {
      if (cursor == null || !cursor.moveToNext())
        return null;

      return getCurrent();
    }

    public MessageRecord getCurrent() {
      long mmsType = cursor.getLong(cursor.getColumnIndexOrThrow(MmsDatabase.MESSAGE_TYPE));

      if (mmsType == PduHeaders.MESSAGE_TYPE_NOTIFICATION_IND) {
        return getNotificationMmsMessageRecord(cursor);
      } else {
        return getMediaMmsMessageRecord(cursor);
      }
    }

    private NotificationMmsMessageRecord getNotificationMmsMessageRecord(Cursor cursor) {
      long      id                   = cursor.getLong(cursor.getColumnIndexOrThrow(MmsDatabase.ID));
      long      dateSent             = cursor.getLong(cursor.getColumnIndexOrThrow(MmsDatabase.NORMALIZED_DATE_SENT));
      long      dateReceived         = cursor.getLong(cursor.getColumnIndexOrThrow(MmsDatabase.NORMALIZED_DATE_RECEIVED));
      long      threadId             = cursor.getLong(cursor.getColumnIndexOrThrow(MmsDatabase.THREAD_ID));
      long      mailbox              = cursor.getLong(cursor.getColumnIndexOrThrow(MmsDatabase.MESSAGE_BOX));
      String    address              = cursor.getString(cursor.getColumnIndexOrThrow(MmsDatabase.ADDRESS));
      int       addressDeviceId      = cursor.getInt(cursor.getColumnIndexOrThrow(MmsDatabase.ADDRESS_DEVICE_ID));
      Recipient recipient            = getRecipientFor(address);

      String    contentLocation      = cursor.getString(cursor.getColumnIndexOrThrow(MmsDatabase.CONTENT_LOCATION));
      String    transactionId        = cursor.getString(cursor.getColumnIndexOrThrow(MmsDatabase.TRANSACTION_ID));
      long      messageSize          = cursor.getLong(cursor.getColumnIndexOrThrow(MmsDatabase.MESSAGE_SIZE));
      long      expiry               = cursor.getLong(cursor.getColumnIndexOrThrow(MmsDatabase.EXPIRY));
      int       status               = cursor.getInt(cursor.getColumnIndexOrThrow(MmsDatabase.STATUS));
      int       deliveryReceiptCount = cursor.getInt(cursor.getColumnIndexOrThrow(MmsDatabase.DELIVERY_RECEIPT_COUNT));
      int       readReceiptCount     = cursor.getInt(cursor.getColumnIndexOrThrow(MmsDatabase.READ_RECEIPT_COUNT));
      int       subscriptionId       = cursor.getInt(cursor.getColumnIndexOrThrow(MmsDatabase.SUBSCRIPTION_ID));

      if (!TextSecurePreferences.isReadReceiptsEnabled(context)) {
        readReceiptCount = 0;
      }

      byte[]contentLocationBytes = null;
      byte[]transactionIdBytes   = null;

      if (!TextUtils.isEmpty(contentLocation))
        contentLocationBytes = Util.toIsoBytes(contentLocation);

      if (!TextUtils.isEmpty(transactionId))
        transactionIdBytes = Util.toIsoBytes(transactionId);

      SlideDeck slideDeck = new SlideDeck(context, new MmsNotificationAttachment(status, messageSize));


      return new NotificationMmsMessageRecord(id, recipient, recipient,
                                              dateSent, dateReceived, deliveryReceiptCount, threadId,
                                              contentLocationBytes, messageSize, expiry, status,
                                              transactionIdBytes, mailbox, slideDeck,
                                              readReceiptCount);
    }

    private MediaMmsMessageRecord getMediaMmsMessageRecord(Cursor cursor) {
      long               id                   = cursor.getLong(cursor.getColumnIndexOrThrow(MmsDatabase.ID));
      long               dateSent             = cursor.getLong(cursor.getColumnIndexOrThrow(MmsDatabase.NORMALIZED_DATE_SENT));
      long               dateReceived         = cursor.getLong(cursor.getColumnIndexOrThrow(MmsDatabase.NORMALIZED_DATE_RECEIVED));
      long               box                  = cursor.getLong(cursor.getColumnIndexOrThrow(MmsDatabase.MESSAGE_BOX));
      long               threadId             = cursor.getLong(cursor.getColumnIndexOrThrow(MmsDatabase.THREAD_ID));
      String             address              = cursor.getString(cursor.getColumnIndexOrThrow(MmsDatabase.ADDRESS));
      int                addressDeviceId      = cursor.getInt(cursor.getColumnIndexOrThrow(MmsDatabase.ADDRESS_DEVICE_ID));
      int                deliveryReceiptCount = cursor.getInt(cursor.getColumnIndexOrThrow(MmsDatabase.DELIVERY_RECEIPT_COUNT));
      int                readReceiptCount     = cursor.getInt(cursor.getColumnIndexOrThrow(MmsDatabase.READ_RECEIPT_COUNT));
      String             body                 = cursor.getString(cursor.getColumnIndexOrThrow(MmsDatabase.BODY));
      int                partCount            = cursor.getInt(cursor.getColumnIndexOrThrow(MmsDatabase.PART_COUNT));
      String             mismatchDocument     = cursor.getString(cursor.getColumnIndexOrThrow(MmsDatabase.MISMATCHED_IDENTITIES));
      String             networkDocument      = cursor.getString(cursor.getColumnIndexOrThrow(MmsDatabase.NETWORK_FAILURE));
      int                subscriptionId       = cursor.getInt(cursor.getColumnIndexOrThrow(MmsDatabase.SUBSCRIPTION_ID));
      long               expiresIn            = cursor.getLong(cursor.getColumnIndexOrThrow(MmsDatabase.EXPIRES_IN));
      long               expireStarted        = cursor.getLong(cursor.getColumnIndexOrThrow(MmsDatabase.EXPIRE_STARTED));
      boolean            unidentified         = cursor.getInt(cursor.getColumnIndexOrThrow(MmsDatabase.UNIDENTIFIED)) == 1;

      if (!TextSecurePreferences.isReadReceiptsEnabled(context)) {
        readReceiptCount = 0;
      }

      Recipient                 recipient          = getRecipientFor(address);
      List<IdentityKeyMismatch> mismatches         = getMismatchedIdentities(mismatchDocument);
      List<NetworkFailure>      networkFailures    = getFailures(networkDocument);
      List<DatabaseAttachment>  attachments        = DatabaseFactory.getAttachmentDatabase(context).getAttachment(cursor);
      List<Contact>             contacts           = getSharedContacts(cursor, attachments);
      Set<Attachment>           contactAttachments = Stream.of(contacts).map(Contact::getAvatarAttachment).filter(a -> a != null).collect(Collectors.toSet());
      List<LinkPreview>         previews           = getLinkPreviews(cursor, attachments);
      Set<Attachment>           previewAttachments = Stream.of(previews).filter(lp -> lp.getThumbnail().isPresent()).map(lp -> lp.getThumbnail().get()).collect(Collectors.toSet());
      SlideDeck                 slideDeck          = getSlideDeck(Stream.of(attachments).filterNot(contactAttachments::contains).filterNot(previewAttachments::contains).toList());
      Quote                     quote              = getQuote(cursor);

      return new MediaMmsMessageRecord(id, recipient, recipient,
                                       addressDeviceId, dateSent, dateReceived, deliveryReceiptCount,
                                       threadId, body, slideDeck, partCount, box, mismatches,
                                       networkFailures, subscriptionId, expiresIn, expireStarted,
                                       readReceiptCount, quote, contacts, previews, unidentified);
    }

    private Recipient getRecipientFor(String serialized) {
      Address address;

      if (TextUtils.isEmpty(serialized) || "insert-address-token".equals(serialized)) {
        address = Address.Companion.getUNKNOWN();
      } else {
        address = Address.fromSerialized(serialized);

      }
      return Recipient.from(context, address, true);
    }

    private List<IdentityKeyMismatch> getMismatchedIdentities(String document) {
      if (!TextUtils.isEmpty(document)) {
        try {
          return JsonUtil.fromJson(document, IdentityKeyMismatchList.class).getList();
        } catch (IOException e) {
          Log.w(TAG, e);
        }
      }

      return new LinkedList<>();
    }

    private List<NetworkFailure> getFailures(String document) {
      if (!TextUtils.isEmpty(document)) {
        try {
          return JsonUtil.fromJson(document, NetworkFailureList.class).getList();
        } catch (IOException ioe) {
          Log.w(TAG, ioe);
        }
      }

      return new LinkedList<>();
    }

    private SlideDeck getSlideDeck(@NonNull List<DatabaseAttachment> attachments) {
      List<? extends Attachment> messageAttachments = Stream.of(attachments)
                                                            .filterNot(Attachment::isQuote)
                                                            .toList();
      return new SlideDeck(context, messageAttachments);
    }

    private @Nullable Quote getQuote(@NonNull Cursor cursor) {
      long                       quoteId          = cursor.getLong(cursor.getColumnIndexOrThrow(MmsDatabase.QUOTE_ID));
      String                     quoteAuthor      = cursor.getString(cursor.getColumnIndexOrThrow(MmsDatabase.QUOTE_AUTHOR));
      String                     quoteText        = cursor.getString(cursor.getColumnIndexOrThrow(MmsDatabase.QUOTE_BODY));
      boolean                    quoteMissing     = cursor.getInt(cursor.getColumnIndexOrThrow(MmsDatabase.QUOTE_MISSING)) == 1;
      List<DatabaseAttachment>   attachments      = DatabaseFactory.getAttachmentDatabase(context).getAttachment(cursor);
      List<? extends Attachment> quoteAttachments = Stream.of(attachments).filter(Attachment::isQuote).toList();
      SlideDeck                  quoteDeck        = new SlideDeck(context, quoteAttachments);

      if (quoteId > 0 && !TextUtils.isEmpty(quoteAuthor)) {
        return new Quote(quoteId, Address.fromExternal(context, quoteAuthor), quoteText, quoteMissing, quoteDeck);
      } else {
        return null;
      }
    }

    @Override
    public void close() {
      if (cursor != null) {
        cursor.close();
      }
    }
  }
}
