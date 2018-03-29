package com.simplemobiletools.clock.activities

import android.annotation.TargetApi
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.WindowManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.simplemobiletools.clock.BuildConfig
import com.simplemobiletools.clock.R
import com.simplemobiletools.clock.adapters.ViewPagerAdapter
import com.simplemobiletools.clock.extensions.config
import com.simplemobiletools.clock.extensions.dbHelper
import com.simplemobiletools.clock.extensions.getNextAlarm
import com.simplemobiletools.clock.extensions.rescheduleEnabledAlarms
import com.simplemobiletools.clock.helpers.*
import com.simplemobiletools.clock.models.AlarmSound
import com.simplemobiletools.commons.extensions.*
import com.simplemobiletools.commons.helpers.LICENSE_NUMBER_PICKER
import com.simplemobiletools.commons.helpers.LICENSE_STETHO
import com.simplemobiletools.commons.helpers.isKitkatPlus
import com.simplemobiletools.commons.models.FAQItem
import kotlinx.android.synthetic.main.activity_main.*
import java.util.*

class MainActivity : SimpleActivity() {
    private var storedUseEnglish = false
    private var storedTextColor = 0
    private var storedBackgroundColor = 0
    private var storedPrimaryColor = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        appLaunched()

        // just get a reference to the database to make sure it is created properly
        dbHelper

        storeStateVariables()
        initFragments()

        if (getNextAlarm().isEmpty()) {
            Thread {
                rescheduleEnabledAlarms()
            }.start()
        }
    }

    override fun onResume() {
        super.onResume()
        if (storedUseEnglish != config.useEnglish) {
            restartActivity()
            return
        }

        val configTextColor = config.textColor
        if (storedTextColor != configTextColor) {
            getInactiveTabIndexes(viewpager.currentItem).forEach {
                main_tabs_holder.getTabAt(it)?.icon?.applyColorFilter(configTextColor)
            }
        }

        val configBackgroundColor = config.backgroundColor
        if (storedBackgroundColor != configBackgroundColor) {
            main_tabs_holder.background = ColorDrawable(configBackgroundColor)
        }

        val configPrimaryColor = config.primaryColor
        if (storedPrimaryColor != configPrimaryColor) {
            main_tabs_holder.setSelectedTabIndicatorColor(getAdjustedPrimaryColor())
            main_tabs_holder.getTabAt(viewpager.currentItem)?.icon?.applyColorFilter(getAdjustedPrimaryColor())
        }

        if (config.preventPhoneFromSleeping) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    override fun onPause() {
        super.onPause()
        storeStateVariables()
        if (config.preventPhoneFromSleeping) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        config.lastUsedViewPagerPage = viewpager.currentItem
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.settings -> launchSettings()
            R.id.about -> launchAbout()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    override fun onNewIntent(intent: Intent) {
        if (intent.extras?.containsKey(OPEN_TAB) == true) {
            viewpager.setCurrentItem(intent.getIntExtra(OPEN_TAB, TAB_CLOCK), false)
        }
    }

    private fun storeStateVariables() {
        config.apply {
            storedTextColor = textColor
            storedBackgroundColor = backgroundColor
            storedPrimaryColor = primaryColor
            storedUseEnglish = useEnglish
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, resultData: Intent?) {
        super.onActivityResult(requestCode, resultCode, resultData)
        if (requestCode == PICK_AUDIO_FILE_INTENT_ID && resultCode == RESULT_OK && resultData != null) {
            storeNewAlarmSound(resultData.data)
        }
    }

    @TargetApi(Build.VERSION_CODES.KITKAT)
    private fun storeNewAlarmSound(uri: Uri) {
        var filename = getFilenameFromUri(uri)
        if (filename.isEmpty()) {
            filename = getString(R.string.alarm)
        }

        val token = object : TypeToken<ArrayList<AlarmSound>>() {}.type
        val yourAlarmSounds = Gson().fromJson<ArrayList<AlarmSound>>(config.yourAlarmSounds, token) ?: ArrayList()
        val newAlarmSoundId = (yourAlarmSounds.maxBy { it.id }?.id ?: YOUR_ALARM_SOUNDS_MIN_ID) + 1
        val newAlarmSound = AlarmSound(newAlarmSoundId, filename, uri.toString())
        if (yourAlarmSounds.firstOrNull { it.uri == uri.toString() } == null) {
            yourAlarmSounds.add(newAlarmSound)
        }

        config.yourAlarmSounds = Gson().toJson(yourAlarmSounds)

        if (isKitkatPlus()) {
            val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            contentResolver.takePersistableUriPermission(uri, takeFlags)
        }

        when (viewpager.currentItem) {
            TAB_TIMER -> (viewpager.adapter as? ViewPagerAdapter)?.updateTimerAlarmSound(newAlarmSound)
        }
    }

    private fun initFragments() {
        viewpager.adapter = ViewPagerAdapter(supportFragmentManager)
        viewpager.onPageChangeListener {
            main_tabs_holder.getTabAt(it)?.select()
        }

        val tabToOpen = intent.getIntExtra(OPEN_TAB, config.lastUsedViewPagerPage)
        intent.removeExtra(OPEN_TAB)
        viewpager.currentItem = tabToOpen
        viewpager.offscreenPageLimit = TABS_COUNT - 1
        main_tabs_holder.onTabSelectionChanged(
                tabUnselectedAction = {
                    it.icon?.applyColorFilter(config.textColor)
                },
                tabSelectedAction = {
                    viewpager.currentItem = it.position
                    it.icon?.applyColorFilter(getAdjustedPrimaryColor())
                }
        )

        setupTabColors(tabToOpen)
    }

    private fun setupTabColors(lastUsedTab: Int) {
        main_tabs_holder.apply {
            background = ColorDrawable(config.backgroundColor)
            setSelectedTabIndicatorColor(getAdjustedPrimaryColor())
            getTabAt(lastUsedTab)?.apply {
                select()
                icon?.applyColorFilter(getAdjustedPrimaryColor())
            }

            getInactiveTabIndexes(lastUsedTab).forEach {
                getTabAt(it)?.icon?.applyColorFilter(config.textColor)
            }
        }
    }

    private fun getInactiveTabIndexes(activeIndex: Int) = arrayListOf(0, 1, 2, 3).filter { it != activeIndex }

    private fun launchSettings() {
        startActivity(Intent(applicationContext, SettingsActivity::class.java))
    }

    private fun launchAbout() {
        val faqItems = arrayListOf(
                FAQItem(R.string.faq_1_title, R.string.faq_1_text),
                FAQItem(R.string.faq_1_title_commons, R.string.faq_1_text_commons),
                FAQItem(R.string.faq_4_title_commons, R.string.faq_4_text_commons),
                FAQItem(R.string.faq_2_title_commons, R.string.faq_2_text_commons)
        )

        startAboutActivity(R.string.app_name, LICENSE_STETHO or LICENSE_NUMBER_PICKER, BuildConfig.VERSION_NAME, faqItems)
    }
}
