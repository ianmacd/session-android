package org.thoughtcrime.securesms.conversation.v2

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import androidx.annotation.DimenRes
import kotlinx.android.synthetic.main.activity_conversation_v2_action_bar.*
import kotlinx.android.synthetic.main.activity_message_detail.*
import network.loki.messenger.R
import org.session.libsession.messaging.MessagingModuleConfiguration
import org.session.libsession.messaging.messages.visible.LinkPreview
import org.session.libsession.messaging.messages.visible.OpenGroupInvitation
import org.session.libsession.messaging.messages.visible.Quote
import org.session.libsession.messaging.messages.visible.VisibleMessage
import org.session.libsession.messaging.sending_receiving.MessageSender
import org.session.libsession.messaging.utilities.UpdateMessageData
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.ExpirationUtil
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsession.utilities.recipients.Recipient
import org.thoughtcrime.securesms.PassphraseRequiredActionBarActivity
import org.thoughtcrime.securesms.conversation.v2.utilities.ResendMessageUtilities
import org.thoughtcrime.securesms.database.DatabaseFactory
import org.thoughtcrime.securesms.database.model.MessageRecord
import org.thoughtcrime.securesms.database.model.MmsMessageRecord
import org.thoughtcrime.securesms.util.DateUtils
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.roundToInt


class MessageDetailActivity: PassphraseRequiredActionBarActivity() {

    var messageRecord: MessageRecord? = null

    // region Settings
    companion object {
        // Extras
        const val MESSAGE_TIMESTAMP = "message_timestamp"
    }
    // endregion

    override fun onCreate(savedInstanceState: Bundle?, ready: Boolean) {
        super.onCreate(savedInstanceState, ready)
        setContentView(R.layout.activity_message_detail)
        title = resources.getString(R.string.conversation_context__menu_message_details)
        val timestamp = intent.getLongExtra(MESSAGE_TIMESTAMP, -1L)
        // We only show this screen for messages fail to send,
        // so the author of the messages must be the current user.
        val author = Address.fromSerialized(TextSecurePreferences.getLocalNumber(this)!!)
        messageRecord = DatabaseFactory.getMmsSmsDatabase (this).getMessageFor(timestamp, author)
        updateContent()
        resend_button.setOnClickListener {
            ResendMessageUtilities.resend(messageRecord!!)
            finish()
        }
    }

    fun updateContent() {
        val dateLocale = Locale.getDefault()
        val dateFormatter: SimpleDateFormat = DateUtils.getDetailedDateFormatter(this, dateLocale)
        sent_time.text = dateFormatter.format(Date(messageRecord!!.dateSent))

        val errorMessage = DatabaseFactory.getLokiMessageDatabase(this).getErrorMessage(messageRecord!!.getId()) ?: "Message failed to send."
        error_message.text = errorMessage

        if (messageRecord!!.getExpiresIn() <= 0 || messageRecord!!.getExpireStarted() <= 0) {
            expires_container.visibility = View.GONE
        } else {
            expires_container.visibility = View.VISIBLE
            val elapsed = System.currentTimeMillis() - messageRecord!!.expireStarted
            val remaining = messageRecord!!.expiresIn - elapsed

            val duration = ExpirationUtil.getExpirationDisplayValue(this, Math.max((remaining / 1000).toInt(), 1))
            expires_in.text = duration
        }
    }
}