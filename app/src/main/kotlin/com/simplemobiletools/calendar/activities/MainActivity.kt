package com.simplemobiletools.calendar.activities

import android.Manifest
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.support.v4.app.ActivityCompat
import android.support.v4.view.ViewPager
import android.util.SparseIntArray
import android.view.Menu
import android.view.MenuItem
import android.widget.TextView
import com.simplemobiletools.calendar.BuildConfig
import com.simplemobiletools.calendar.R
import com.simplemobiletools.calendar.adapters.MyMonthPagerAdapter
import com.simplemobiletools.calendar.adapters.MyWeekPagerAdapter
import com.simplemobiletools.calendar.adapters.MyYearPagerAdapter
import com.simplemobiletools.calendar.dialogs.ChangeViewDialog
import com.simplemobiletools.calendar.dialogs.FilterEventTypesDialog
import com.simplemobiletools.calendar.dialogs.ImportEventsDialog
import com.simplemobiletools.calendar.extensions.config
import com.simplemobiletools.calendar.extensions.launchNewEventIntent
import com.simplemobiletools.calendar.extensions.seconds
import com.simplemobiletools.calendar.extensions.updateWidgets
import com.simplemobiletools.calendar.fragments.EventListFragment
import com.simplemobiletools.calendar.fragments.WeekFragment
import com.simplemobiletools.calendar.helpers.*
import com.simplemobiletools.calendar.helpers.Formatter
import com.simplemobiletools.calendar.interfaces.NavigationListener
import com.simplemobiletools.calendar.views.MyScrollView
import com.simplemobiletools.commons.dialogs.FilePickerDialog
import com.simplemobiletools.commons.extensions.*
import com.simplemobiletools.commons.helpers.LICENSE_JODA
import com.simplemobiletools.commons.helpers.LICENSE_KOTLIN
import com.simplemobiletools.commons.helpers.LICENSE_STETHO
import com.simplemobiletools.commons.models.Release
import kotlinx.android.synthetic.main.activity_main.*
import org.joda.time.DateTime
import java.io.*
import java.util.*

class MainActivity : SimpleActivity(), NavigationListener {
    private val PREFILLED_MONTHS = 73
    private val PREFILLED_YEARS = 21
    private val PREFILLED_WEEKS = 41
    private val STORAGE_PERMISSION_IMPORT = 1
    private val STORAGE_PERMISSION_EXPORT = 2

    private var mIsMonthSelected = false
    private var mStoredTextColor = 0
    private var mStoredBackgroundColor = 0
    private var mStoredPrimaryColor = 0
    private var mStoredIsSundayFirst = false
    private var mStoredUse24HourFormat = false
    private var mShouldFilterBeVisible = false

    private var mDefaultWeeklyPage = 0
    private var mDefaultMonthlyPage = 0
    private var mDefaultYearlyPage = 0

    companion object {
        var mWeekScrollY = 0
        var eventTypeColors = SparseIntArray()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        calendar_fab.setOnClickListener { launchNewEventIntent() }
        checkWhatsNewDialog()
        storeStoragePaths()
        if (resources.getBoolean(R.bool.portrait_only))
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }

    override fun onResume() {
        super.onResume()
        if (mStoredTextColor != config.textColor || mStoredBackgroundColor != config.backgroundColor || mStoredPrimaryColor != config.primaryColor)
            updateViewPager()

        DBHelper.newInstance(applicationContext).getEventTypes {
            eventTypeColors.clear()
            it.map { eventTypeColors.put(it.id, it.color) }
            mShouldFilterBeVisible = eventTypeColors.size() > 1 || config.displayEventTypes.isEmpty()
            invalidateOptionsMenu()
        }
        mStoredTextColor = config.textColor
        mStoredPrimaryColor = config.primaryColor
        mStoredBackgroundColor = config.backgroundColor

        if (config.storedView == WEEKLY_VIEW) {
            if (mStoredIsSundayFirst != config.isSundayFirst || mStoredUse24HourFormat != config.use24hourFormat) {
                fillWeeklyViewPager()
            }
        }

        updateWidgets()
        updateTextColors(calendar_coordinator)
    }

    override fun onPause() {
        super.onPause()
        mStoredTextColor = config.textColor
        mStoredIsSundayFirst = config.isSundayFirst
        mStoredBackgroundColor = config.backgroundColor
        mStoredPrimaryColor = config.primaryColor
        mStoredUse24HourFormat = config.use24hourFormat
    }

    override fun onDestroy() {
        super.onDestroy()
        config.isFirstRun = false
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        menu.findItem(R.id.filter).isVisible = mShouldFilterBeVisible
        menu.findItem(R.id.go_to_today).isVisible = shouldGoToTodayBeVisible()
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.change_view -> showViewDialog()
            R.id.go_to_today -> goToToday()
            R.id.filter -> showFilterDialog()
            R.id.import_events -> tryImportEvents()
            R.id.export_raw -> tryExportDatabase()
            R.id.settings -> launchSettings()
            R.id.about -> launchAbout()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    override fun onBackPressed() {
        if (mIsMonthSelected && config.storedView == YEARLY_VIEW) {
            updateView(YEARLY_VIEW)
        } else {
            super.onBackPressed()
        }
    }

    private fun showViewDialog() {
        ChangeViewDialog(this) {
            updateView(it)
            invalidateOptionsMenu()
        }
    }

    private fun goToToday() {
        if (config.storedView == WEEKLY_VIEW) {
            week_view_view_pager.currentItem = mDefaultWeeklyPage
        } else if (config.storedView == MONTHLY_VIEW) {
            main_view_pager.currentItem = mDefaultMonthlyPage
        } else if (config.storedView == YEARLY_VIEW) {
            main_view_pager.currentItem = mDefaultYearlyPage
        }
    }

    private fun shouldGoToTodayBeVisible(): Boolean {
        return if (config.storedView == WEEKLY_VIEW) {
            week_view_view_pager.currentItem != mDefaultWeeklyPage
        } else if (config.storedView == MONTHLY_VIEW) {
            main_view_pager.currentItem != mDefaultMonthlyPage
        } else if (config.storedView == YEARLY_VIEW) {
            main_view_pager.currentItem != mDefaultYearlyPage
        } else {
            false
        }
    }

    private fun showFilterDialog() {
        FilterEventTypesDialog(this) {
            refreshViewPager()
        }
    }

    private fun updateView(view: Int) {
        calendar_fab.beGoneIf(view == YEARLY_VIEW)
        mIsMonthSelected = view == MONTHLY_VIEW
        config.storedView = view
        updateViewPager()
    }

    private fun updateViewPager() {
        resetTitle()
        if (config.storedView == YEARLY_VIEW) {
            fillYearlyViewPager()
        } else if (config.storedView == EVENTS_LIST_VIEW) {
            fillEventsList()
        } else if (config.storedView == WEEKLY_VIEW) {
            fillWeeklyViewPager()
        } else {
            val targetDay = DateTime().toString(Formatter.DAYCODE_PATTERN)
            fillMonthlyViewPager(targetDay)
        }

        mWeekScrollY = 0
    }

    private fun refreshViewPager() {
        if (config.storedView == YEARLY_VIEW) {
            (main_view_pager.adapter as MyYearPagerAdapter).refreshEvents(main_view_pager.currentItem)
        } else if (config.storedView == EVENTS_LIST_VIEW) {
            fillEventsList()
        } else if (config.storedView == WEEKLY_VIEW) {
            (week_view_view_pager.adapter as MyWeekPagerAdapter).refreshEvents(week_view_view_pager.currentItem)
        } else {
            (main_view_pager.adapter as MyMonthPagerAdapter).refreshEvents(main_view_pager.currentItem)
        }
    }

    private fun tryImportEvents() {
        if (hasReadStoragePermission()) {
            importEvents()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), STORAGE_PERMISSION_IMPORT)
        }
    }

    private fun importEvents() {
        FilePickerDialog(this) {
            if (it.toLowerCase().endsWith(".ics")) {
                ImportEventsDialog(this, it) {
                    if (it) {
                        updateViewPager()
                    }
                }
            } else {
                toast(R.string.invalid_file_format)
            }
        }
    }

    private fun tryExportDatabase() {
        if (hasWriteStoragePermission()) {
            exportDatabase()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), STORAGE_PERMISSION_EXPORT)
        }
    }

    private fun exportDatabase() {
        FilePickerDialog(this, pickFile = false) {
            val source = getDatabasePath(DBHelper.DB_NAME)
            val destination = File(it, DBHelper.DB_NAME)
            if (isShowingPermDialog(destination)) {
                return@FilePickerDialog
            }

            Thread({
                if (source.exists()) {
                    val inputStream = FileInputStream(source)
                    val outputStream: OutputStream?

                    if (needsStupidWritePermissions(destination.absolutePath)) {
                        var document = getFileDocument(destination.absolutePath, config.treeUri) ?: return@Thread
                        if (!destination.exists()) {
                            document = document.createFile("", destination.name)
                        }
                        outputStream = contentResolver.openOutputStream(document.uri)
                    } else {
                        outputStream = FileOutputStream(destination)
                    }

                    copyStream(inputStream, outputStream)
                    inputStream.close()
                    outputStream?.close()

                    runOnUiThread {
                        toast(R.string.database_exported_successfully)
                    }
                }
            }).start()
        }
    }

    private fun copyStream(inputStream: InputStream, out: OutputStream?) {
        val buf = ByteArray(1024)
        var len: Int
        while (true) {
            len = inputStream.read(buf)
            if (len <= 0)
                break
            out?.write(buf, 0, len)
        }
    }

    private fun launchSettings() {
        startActivity(Intent(applicationContext, SettingsActivity::class.java))
    }

    private fun launchAbout() {
        startAboutActivity(R.string.app_name, LICENSE_KOTLIN or LICENSE_JODA or LICENSE_STETHO, BuildConfig.VERSION_NAME)
    }

    private fun resetTitle() {
        title = getString(R.string.app_launcher_name)
        supportActionBar?.subtitle = ""
    }

    private fun fillMonthlyViewPager(targetDay: String) {
        main_weekly_scrollview.beGone()
        calendar_fab.beVisible()
        val codes = getMonths(targetDay)
        val monthlyAdapter = MyMonthPagerAdapter(supportFragmentManager, codes, this)
        mDefaultMonthlyPage = codes.size / 2

        main_view_pager.apply {
            adapter = monthlyAdapter
            beVisible()
            addOnPageChangeListener(object : ViewPager.OnPageChangeListener {
                override fun onPageScrollStateChanged(state: Int) {
                }

                override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {
                }

                override fun onPageSelected(position: Int) {
                    invalidateOptionsMenu()
                }
            })
            currentItem = mDefaultMonthlyPage
        }
        calendar_event_list_holder.beGone()
    }

    private fun getMonths(code: String): List<String> {
        val months = ArrayList<String>(PREFILLED_MONTHS)
        val today = Formatter.getDateTimeFromCode(code)
        for (i in -PREFILLED_MONTHS / 2..PREFILLED_MONTHS / 2) {
            months.add(Formatter.getDayCodeFromDateTime(today.plusMonths(i)))
        }

        return months
    }

    private fun fillWeeklyViewPager() {
        var thisweek = DateTime().withDayOfWeek(1).withTimeAtStartOfDay().minusDays(if (config.isSundayFirst) 1 else 0)
        if (DateTime().minusDays(7).seconds() > thisweek.seconds()) {
            thisweek = thisweek.plusDays(7)
        }
        val weekTSs = getWeekTimestamps(thisweek.seconds())
        val weeklyAdapter = MyWeekPagerAdapter(supportFragmentManager, weekTSs, object : WeekFragment.WeekScrollListener {
            override fun scrollTo(y: Int) {
                week_view_hours_scrollview.scrollY = y
                mWeekScrollY = y
            }
        })
        main_view_pager.beGone()
        calendar_event_list_holder.beGone()
        main_weekly_scrollview.beVisible()

        week_view_hours_holder.removeAllViews()
        val hourDateTime = DateTime().withDate(2000, 1, 1).withTime(0, 0, 0, 0)
        for (i in 1..23) {
            val formattedHours = Formatter.getHours(this, hourDateTime.withHourOfDay(i))
            (layoutInflater.inflate(R.layout.weekly_view_hour_textview, null, false) as TextView).apply {
                text = formattedHours
                setTextColor(mStoredTextColor)
                week_view_hours_holder.addView(this)
            }
        }

        mDefaultWeeklyPage = weekTSs.size / 2
        week_view_view_pager.apply {
            adapter = weeklyAdapter
            addOnPageChangeListener(object : ViewPager.OnPageChangeListener {
                override fun onPageScrollStateChanged(state: Int) {
                }

                override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {
                }

                override fun onPageSelected(position: Int) {
                    invalidateOptionsMenu()
                    setupActionbarTitle(weekTSs[position])
                }
            })
            currentItem = mDefaultWeeklyPage
        }

        week_view_hours_scrollview.setOnScrollviewListener(object : MyScrollView.ScrollViewListener {
            override fun onScrollChanged(scrollView: MyScrollView, x: Int, y: Int, oldx: Int, oldy: Int) {
                mWeekScrollY = y
                weeklyAdapter.updateScrollY(week_view_view_pager.currentItem, y)
            }
        })
        week_view_hours_scrollview.setOnTouchListener({ view, motionEvent -> true })
    }

    fun updateHoursTopMargin(margin: Int) {
        week_view_hours_divider.layoutParams.height = margin
        week_view_hours_scrollview.requestLayout()
    }

    private fun getWeekTimestamps(targetWeekTS: Int): List<Int> {
        val weekTSs = ArrayList<Int>(PREFILLED_WEEKS)
        for (i in -PREFILLED_WEEKS / 2..PREFILLED_WEEKS / 2) {
            weekTSs.add(targetWeekTS + i * WEEK_SECONDS)
        }
        return weekTSs
    }

    private fun setupActionbarTitle(timestamp: Int) {
        val startDateTime = Formatter.getDateTimeFromTS(timestamp)
        val endDateTime = Formatter.getDateTimeFromTS(timestamp + WEEK_SECONDS)
        val startMonthName = Formatter.getMonthName(this, startDateTime.monthOfYear)
        if (startDateTime.monthOfYear == endDateTime.monthOfYear) {
            var newTitle = startMonthName
            if (startDateTime.year != DateTime().year)
                newTitle += " - ${startDateTime.year}"
            title = newTitle
        } else {
            val endMonthName = Formatter.getMonthName(this, endDateTime.monthOfYear)
            title = "$startMonthName - $endMonthName"
        }
        supportActionBar?.subtitle = "${getString(R.string.week)} ${startDateTime.plusDays(3).weekOfWeekyear}"
    }

    private fun fillYearlyViewPager() {
        main_weekly_scrollview.beGone()
        calendar_fab.beGone()
        val targetYear = DateTime().toString(Formatter.YEAR_PATTERN).toInt()
        val years = getYears(targetYear)
        val yearlyAdapter = MyYearPagerAdapter(supportFragmentManager, years, this)

        mDefaultYearlyPage = years.size / 2
        main_view_pager.apply {
            adapter = yearlyAdapter
            addOnPageChangeListener(object : ViewPager.OnPageChangeListener {
                override fun onPageScrollStateChanged(state: Int) {
                }

                override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {
                }

                override fun onPageSelected(position: Int) {
                    invalidateOptionsMenu()
                    if (position < years.size)
                        title = "${getString(R.string.app_launcher_name)} - ${years[position]}"
                }
            })
            currentItem = mDefaultYearlyPage
            beVisible()
        }
        title = "${getString(R.string.app_launcher_name)} - ${years[years.size / 2]}"
        calendar_event_list_holder.beGone()
    }

    private fun getYears(targetYear: Int): List<Int> {
        val years = ArrayList<Int>(PREFILLED_YEARS)
        years += targetYear - PREFILLED_YEARS / 2..targetYear + PREFILLED_YEARS / 2
        return years
    }

    private fun fillEventsList() {
        main_view_pager.adapter = null
        main_view_pager.beGone()
        main_weekly_scrollview.beGone()
        calendar_event_list_holder.beVisible()
        supportFragmentManager.beginTransaction().replace(R.id.calendar_event_list_holder, EventListFragment(), "").commit()
    }

    override fun goLeft() {
        main_view_pager.currentItem = main_view_pager.currentItem - 1
    }

    override fun goRight() {
        main_view_pager.currentItem = main_view_pager.currentItem + 1
    }

    override fun goToDateTime(dateTime: DateTime) {
        fillMonthlyViewPager(Formatter.getDayCodeFromDateTime(dateTime))
        mIsMonthSelected = true
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            if (requestCode == STORAGE_PERMISSION_IMPORT) {
                importEvents()
            } else if (requestCode == STORAGE_PERMISSION_EXPORT) {
                exportDatabase()
            }
        }
    }

    private fun checkWhatsNewDialog() {
        arrayListOf<Release>().apply {
            add(Release(39, R.string.release_39))
            add(Release(40, R.string.release_40))
            add(Release(42, R.string.release_42))
            add(Release(44, R.string.release_44))
            add(Release(46, R.string.release_46))
            add(Release(48, R.string.release_48))
            add(Release(49, R.string.release_49))
            add(Release(51, R.string.release_51))
            add(Release(52, R.string.release_52))
            add(Release(54, R.string.release_54))
            add(Release(57, R.string.release_57))
            add(Release(59, R.string.release_59))
            add(Release(60, R.string.release_60))
            add(Release(62, R.string.release_62))
            checkWhatsNew(this, BuildConfig.VERSION_CODE)
        }
    }
}
