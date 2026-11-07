package strap.science

// The strap's sport codes (workout summary field 3.1, spec/01 §7.2), as OpenStrap lists them
// (github.com/sayed0am/openstrap, app/.../data/Workouts.kt and SportTypes.kt, Apache-2.0),
// from its static analysis of the Zepp app. Codes not listed are shown with their number, so
// a user can report what they were doing.

/** Names and kinds of the strap's sport codes. */
public object Sports {
    /** No sport known: the strap sent none, or the workout was stored before 0.7.1 read it (spec/01 §7.2). */
    public const val UNKNOWN: Int = 0

    /** What the strap files movement it logged by itself under, rather than a sport. */
    public const val ACTIVITY: Int = 223

    /** Summary field 3.2 for a workout the strap recognised by itself (OpenStrap, codemap 05 §8). */
    public const val SUBTYPE_AUTO_DETECTED: Int = 1

    public enum class Kind { RUN, WALK, HIKE, RIDE, SWIM, STRENGTH, CLIMB, OTHER }

    /** The sport's name, or null when the code isn't in the table. */
    public fun name(code: Int): String? = NAMES[code]

    /** What a workout is called: its sport, "Workout (code N)" for an unlisted code, "Workout" with none. */
    public fun label(code: Int): String = when (code) {
        UNKNOWN -> "Workout"
        else -> NAMES[code] ?: "Workout (code $code)"
    }

    public fun kind(code: Int): Kind = when (code) {
        1, 2, 11 -> Kind.RUN
        3, 40, 215, 225 -> Kind.WALK
        15, 224 -> Kind.HIKE
        4, 8, 143 -> Kind.RIDE
        6, 7 -> Kind.SWIM
        24, 50, 52, 222, 226 -> Kind.STRENGTH
        10, 70 -> Kind.CLIMB
        else -> Kind.OTHER
    }

    private val NAMES: Map<Int, String> = mapOf(
        1 to "Outdoor run", 2 to "Treadmill", 3 to "Walk", 4 to "Outdoor cycling",
        5 to "Free training", 6 to "Pool swim", 7 to "Open water swim", 8 to "Indoor cycling",
        9 to "Elliptical", 10 to "Climbing", 11 to "Trail run", 12 to "Skiing",
        15 to "Hiking", 16 to "Free training", 17 to "Tennis", 18 to "Soccer",
        21 to "Jump rope", 23 to "Rowing machine", 24 to "Indoor fitness", 40 to "Indoor walk",
        49 to "HIIT", 50 to "Core training", 51 to "Mixed aerobic", 52 to "Strength training",
        53 to "Stretching", 55 to "Flexibility", 57 to "Step training", 58 to "Stair stepper",
        60 to "Yoga", 61 to "Pilates", 70 to "Rock climbing", 76 to "Dance",
        83 to "Golf", 85 to "Basketball", 88 to "Volleyball", 89 to "Table tennis",
        92 to "Badminton", 97 to "Boxing", 108 to "Stair climbing", 109 to "Aerobics",
        130 to "Cross training", 143 to "Spinning", 191 to "Soccer",
        204 to "Pickleball", 205 to "Padel", 215 to "Dog walking", 216 to "Padel",
        222 to "HYROX training", 223 to "Activity", 224 to "Mountain hike", 225 to "Rucking",
        226 to "Hybrid training", 227 to "Pickleball",
    )
}
