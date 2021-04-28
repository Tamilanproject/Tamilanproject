package com.gowtham.letschat.di

import android.content.Context
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.FirebaseFirestore
import com.gowtham.letschat.db.DbRepository
import com.gowtham.letschat.db.DefaultDbRepo
import com.gowtham.letschat.db.daos.ChatUserDao
import com.gowtham.letschat.db.daos.GroupDao
import com.gowtham.letschat.db.daos.GroupMessageDao
import com.gowtham.letschat.db.daos.MessageDao
import com.gowtham.letschat.ui.activities.MainActivity
import com.gowtham.letschat.utils.MPreference
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.internal.managers.ApplicationComponentManager
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class MessageCollection

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class GroupCollection

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Singleton
    @Provides
    fun provideUsersCollectionRef(): CollectionReference {
        val db = FirebaseFirestore.getInstance()
        return db.collection("Users")
    }

    @MessageCollection
    @Singleton
    @Provides
    fun provideMessagesCollectionRef(): CollectionReference {
        val db = FirebaseFirestore.getInstance()
        return db.collection("Messages")
    }

    @GroupCollection
    @Singleton
    @Provides
    fun provideGroupCollectionRef(): CollectionReference {
        val db = FirebaseFirestore.getInstance()
        return db.collection("Groups")
    }

    @Provides
    fun provideMainActivity(): MainActivity {
        return MainActivity()
    }

    @Provides
    fun provideDefaultDbRepo(@ApplicationContext context: Context,
                             userDao: ChatUserDao,
                             preference: MPreference,
                             groupDao: GroupDao,
                             groupMsgDao: GroupMessageDao,
                             messageDao: MessageDao): DefaultDbRepo {
        return DbRepository(context, userDao, preference, groupDao, groupMsgDao, messageDao)
    }




}