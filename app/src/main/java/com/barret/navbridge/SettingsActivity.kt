package com.barret.navbridge

import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import com.barret.navbridge.databinding.ActivitySettingsBinding

/**
 * Routing profile, language and turn guidance. All three used to be either on
 * the main screen or nowhere.
 *
 * There is no Save button anywhere on this screen, deliberately. Every control
 * writes its preference the instant it changes, and every consumer reads it
 * fresh rather than caching it at startup: the routing profile is read per
 * route request, the cue options per cue, the language on the next string
 * lookup. So a setting changed mid-ride takes effect on the next turn, with no
 * Stop/Start cycle and nothing to forget to confirm.
 */
class SettingsActivity : LocaleAwareActivity() {

    private lateinit var binding: ActivitySettingsBinding

    // BRouter's own profile names (see IBRouterService.aidl's "v" param) --
    // index must line up with R.array.routing_profile_labels, position for
    // position. Same list the firmware sends in RRQ1's profile field.
    private val routingProfileValues = listOf("bicycle", "motorcar", "foot")

    // Plays the Test button's cue. Deliberately its own short-lived instance
    // rather than reaching into the service: Settings has to work whether or
    // not forwarding is running, and this is the one place a cue is triggered
    // by a person instead of by the board.
    private var testAnnouncer: TurnCueAnnouncer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.apply {
            title = getString(R.string.settings_title)
            setDisplayHomeAsUpEnabled(true)
        }

        setupProfileSpinner()
        setupLanguageSpinner()
        setupCueControls()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onDestroy() {
        testAnnouncer?.stop()
        testAnnouncer = null
        super.onDestroy()
    }

    private fun prefs() = getSharedPreferences(LocaleHelper.PREFS, MODE_PRIVATE)

    private fun spinnerAdapter(items: Array<String>) =
        ArrayAdapter(this, android.R.layout.simple_spinner_item, items).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

    /**
     * Spinner listeners fire once during setSelection() as well as on real user
     * input, which would mean writing a preference (and, for language,
     * recreating the Activity) on every visit to this screen. Guarding on
     * "the value actually changed" is what keeps that from happening.
     */
    private fun setupProfileSpinner() {
        binding.spinnerProfile.adapter = spinnerAdapter(resources.getStringArray(R.array.routing_profile_labels))
        val saved = prefs().getString("routing_profile", "bicycle") ?: "bicycle"
        binding.spinnerProfile.setSelection(routingProfileValues.indexOf(saved).coerceAtLeast(0))

        binding.spinnerProfile.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val value = routingProfileValues[position]
                if (value != prefs().getString("routing_profile", "bicycle")) {
                    prefs().edit().putString("routing_profile", value).apply()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupLanguageSpinner() {
        binding.spinnerLanguage.adapter = spinnerAdapter(resources.getStringArray(R.array.language_labels))
        binding.spinnerLanguage.setSelection(LocaleHelper.savedIndex(this))

        binding.spinnerLanguage.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val tag = LocaleHelper.TAGS[position]
                if (tag == LocaleHelper.savedTag(this@SettingsActivity)) return

                LocaleHelper.set(this@SettingsActivity, tag)
                // The running service holds a TTS engine that is still pointed
                // at the old voice; tell it to re-resolve before the next turn
                // rather than after it.
                NmeaForwardService.onLanguageChanged()
                // setApplicationLocales already triggers a recreate on most
                // versions, but not reliably on all of them -- doing it
                // explicitly means the labels on THIS screen are never left in
                // the previous language.
                recreate()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupCueControls() {
        binding.spinnerCueMode.adapter = spinnerAdapter(resources.getStringArray(R.array.cue_mode_labels))
        val mode = prefs().getInt(TurnCueAnnouncer.KEY_CUE_MODE, TurnCueAnnouncer.MODE_SPEECH)
        binding.spinnerCueMode.setSelection(mode.coerceIn(0, 2))

        binding.spinnerCueMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position != prefs().getInt(TurnCueAnnouncer.KEY_CUE_MODE, TurnCueAnnouncer.MODE_SPEECH)) {
                    prefs().edit().putInt(TurnCueAnnouncer.KEY_CUE_MODE, position).apply()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        binding.switchVibration.isChecked = prefs().getBoolean(TurnCueAnnouncer.KEY_VIBRATION, true)
        binding.switchVibration.setOnCheckedChangeListener { _, checked ->
            prefs().edit().putBoolean(TurnCueAnnouncer.KEY_VIBRATION, checked).apply()
        }

        // Default on: this is the only cue that reaches a wrist, and the
        // notification is silent on the phone, so leaving it enabled costs a
        // rider who does not own a band nothing they will notice.
        binding.switchNotify.isChecked = prefs().getBoolean(TurnCueAnnouncer.KEY_NOTIFY, true)
        binding.switchNotify.setOnCheckedChangeListener { _, checked ->
            prefs().edit().putBoolean(TurnCueAnnouncer.KEY_NOTIFY, checked).apply()
        }

        // A real cue, through the real path, with the settings as they stand.
        // Turn guidance is the one feature here that cannot be verified from
        // an armchair -- without this you would have to go and ride to a
        // junction to find out whether the volume, the voice or the headset
        // routing was right.
        binding.btnTestCue.setOnClickListener {
            val announcer = testAnnouncer ?: TurnCueAnnouncer(applicationContext).also {
                it.start()
                testAnnouncer = it
            }
            // TTS init is asynchronous, so the very first tap can arrive before
            // the engine is ready and would come out as a beep. A short delay
            // covers that without making the button feel laggy.
            binding.btnTestCue.postDelayed({
                announcer.announce("PREP", "left", 0, 300)
            }, 350)
        }
    }
}
