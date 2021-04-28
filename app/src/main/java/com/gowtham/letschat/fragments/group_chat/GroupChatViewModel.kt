package com.gowtham.letschat.fragments.group_chat

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.ListenerRegistration
import com.gowtham.letschat.TYPE_NEW_GROUP_MESSAGE
import com.gowtham.letschat.core.GroupMsgSender
import com.gowtham.letschat.core.GroupMsgStatusUpdater
import com.gowtham.letschat.core.OnGrpMessageResponse
import com.gowtham.letschat.db.daos.ChatUserDao
import com.gowtham.letschat.db.daos.GroupDao
import com.gowtham.letschat.db.daos.GroupMessageDao
import com.gowtham.letschat.db.data.Group
import com.gowtham.letschat.db.data.GroupMessage
import com.gowtham.letschat.di.GroupCollection
import com.gowtham.letschat.fragments.single_chat.toDataClass
import com.gowtham.letschat.services.GroupUploadWorker
import com.gowtham.letschat.utils.Constants
import com.gowtham.letschat.utils.LogMessage
import com.gowtham.letschat.utils.MPreference
import com.gowtham.letschat.utils.UserUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class GroupChatViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preference: MPreference,
    private val groupDao: GroupDao,
    private val groupMsgDao: GroupMessageDao,
    private val chatUserDao: ChatUserDao,
    @GroupCollection
    private val groupCollection: CollectionReference) : ViewModel() {

    private val messagesList: MutableList<GroupMessage> by lazy { mutableListOf() }

    val message = MutableLiveData<String>()

    val typingUsers = MutableLiveData<String>()

    private val currentGroup = preference.getOnlineGroup()

    private val fromUser = preference.getUid()

    private val database = FirebaseDatabase.getInstance()

    private var isTyping = false

    private var groupListener: ListenerRegistration? = null

    private val typingHandler = Handler(Looper.getMainLooper())

    private var chatsFromRoom = ArrayList<GroupMessage>()

    private var canScroll = false

    private var statusUpdated = false

    private var isOnline = true

    private var cleared = false

    private lateinit var group: Group

    private val statusRef: DatabaseReference = database.getReference("Groups/$currentGroup")

    init {
        groupListener?.remove()
        groupListener = groupCollection.document(currentGroup)
            .collection("group_messages").addSnapshotListener { snapshot, error ->
                if (cleared)
                    return@addSnapshotListener
                val docs = snapshot?.documents
                if (snapshot?.metadata?.isFromCache!!)
                    return@addSnapshotListener

                if (error == null) {
                    messagesList.clear()
                    if (docs.isNullOrEmpty())
                        return@addSnapshotListener
                    docs.forEach { doc ->
                        val message = doc.data?.toDataClass<GroupMessage>()
                            messagesList.add(message!!)
                    }
                    if (!messagesList.isNullOrEmpty()) {
                        Timber.v("Check state one")
                        updateMessagesStatus()
                    }
                }
            }

        groupCollection.document(currentGroup).addSnapshotListener { value, error ->
            try {
                if (error == null) {
                    val list = value?.get("typing_users")
                    var users =if (list == null) ArrayList()
                               else list as ArrayList<String>
                    val names=group.members?.filter { users.contains(it.id) && it.id!=fromUser }?.map { //get locally saved name
                        it.localName +" is typing..."
                    }
                    if (users.isNullOrEmpty())
                        typingUsers.postValue("")
                    else
                        typingUsers.postValue(TextUtils.join(",", names!!))
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun getGroupMessages(groupId: String) = groupMsgDao.getChatsOfGroup(groupId)

    fun getChatUsers() = chatUserDao.getAllChatUser()

    fun setGroup(group: Group) {
        if (!this::group.isInitialized)
            this.group = group
    }

    private fun updateMessagesStatus() {
        LogMessage.v("Last Message is ${messagesList.last().textMessage?.text}")
        viewModelScope.launch {
            groupMsgDao.insertMultipleMessage(messagesList)
        }
        if (isOnline) {
            val updateToSeen = GroupMsgStatusUpdater(groupCollection)
            updateToSeen.updateToSeen(fromUser!!, messagesList, currentGroup)
        }
    }

    fun setChatsOfThisUser(list: MutableList<GroupMessage>) {
        chatsFromRoom = list as ArrayList<GroupMessage>
        if (!statusUpdated) {
            statusUpdated = true
            setSeenAllMessage()  //one time only
        }
    }

    fun setSeenAllMessage() {
        if (!messagesList.isNullOrEmpty() && isOnline) {
            val updateToSeen = GroupMsgStatusUpdater(groupCollection)
            updateToSeen.updateToSeen(fromUser!!, messagesList, currentGroup)
        } else if (!chatsFromRoom.isNullOrEmpty() && isOnline) {
            val updateToSeen = GroupMsgStatusUpdater(groupCollection)
            updateToSeen.updateToSeen(fromUser!!, chatsFromRoom, currentGroup)
        }
        if (isOnline)
            UserUtils.setUnReadCountGroup(groupDao, group)
    }

    fun canScroll(can: Boolean) {
        canScroll = can
    }

    fun getCanScroll() = canScroll

    fun setOnline(online: Boolean) {
        isOnline = online
    }

    fun sendTyping(edtValue: String) {
        if (edtValue.isEmpty()) {
            if (isTyping)
                sendTypingStatus(false, fromUser!!, currentGroup)
            isTyping = false
        } else if (!isTyping) {
            sendTypingStatus(true, fromUser!!, currentGroup)
            isTyping = true
            removeTypingCallbacks()
            typingHandler.postDelayed(typingThread, 4000)
        }
    }

    private fun sendTypingStatus(
        isTyping: Boolean,
        fromUser: String, currentGroup: String) {
        val value =
            if (isTyping) FieldValue.arrayUnion(fromUser) else FieldValue.arrayRemove(fromUser)
        groupCollection.document(currentGroup).update("typing_users", value)
    }

    private val typingThread = Runnable {
        isTyping = false
        sendTypingStatus(false, fromUser!!, currentGroup)
        removeTypingCallbacks()
    }

    private fun removeTypingCallbacks() {
        typingHandler.removeCallbacks(typingThread)
    }

    fun sendCachedTxtMesssages() {
        CoroutineScope(Dispatchers.IO).launch {
            updateCacheMessges(groupMsgDao.getChatsOfGroupList(currentGroup))
        }
    }

    private suspend fun updateCacheMessges(chatsOfGroup: List<GroupMessage>) {
        withContext(Dispatchers.Main) {
            val nonSendMsgs = chatsOfGroup.filter { it.from == fromUser
                    && it.status[0] == 0 && it.type=="text"}
            LogMessage.v("nonSendMsgs Group Size ${nonSendMsgs.size}")
            for (cachedMsg in nonSendMsgs) {
                val messageSender = GroupMsgSender(groupCollection)
                messageSender.sendMessage(cachedMsg, group, messageListener)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        cleared = true
        groupListener?.remove()
    }

    fun sendMessage(message: GroupMessage) {
        Handler(Looper.getMainLooper()).postDelayed({
            val messageSender = GroupMsgSender(groupCollection)
            messageSender.sendMessage(message, group, messageListener)
        }, 300)
        UserUtils.insertGroupMsg(groupMsgDao, message)
    }

    fun uploadToCloud(message: GroupMessage, fileUri: String){
        try {
            UserUtils.insertGroupMsg(groupMsgDao, message)
            removeTypingCallbacks()
            val messageData=Json.encodeToString(message)
            val groupData=Json.encodeToString(group)
            val data= Data.Builder()
                .putString(Constants.MESSAGE_FILE_URI,fileUri)
                .putString(Constants.MESSAGE_DATA,messageData)
                .putString(Constants.GROUP_DATA,groupData)
                .build()
            val uploadWorkRequest: WorkRequest =
                OneTimeWorkRequestBuilder<GroupUploadWorker>()
                    .setInputData(data)
                    .build()
            WorkManager.getInstance(context).enqueue(uploadWorkRequest)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }


    private val messageListener = object : OnGrpMessageResponse {
        override fun onSuccess(message: GroupMessage) {
            LogMessage.v("messageListener OnSuccess ${message.textMessage?.text}")
            UserUtils.insertGroupMsg(groupMsgDao, message)
            val users = group.members?.filter { !it.user.token.isEmpty() }?.map {
                it.user.token
                it
            }
            users?.forEach {
                UserUtils.sendPush(
                    context, TYPE_NEW_GROUP_MESSAGE,
                    Json.encodeToString(message), it.user.token.toString(), it.id
                )
            }
        }

        override fun onFailed(message: GroupMessage) {
            LogMessage.v("messageListener onFailed ${message.createdAt}")
            UserUtils.insertGroupMsg(groupMsgDao, message)
        }
    }

}