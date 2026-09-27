"""Body mass index, shared by the profile and the Jurca model.

Knowledge: [[weight_bmi_body_composition]]. This is mass/height², not a measurement
of body composition. Extracted verbatim from derive.vo2max.
"""


def body_mass_index(weight_kg: float, height_cm: float) -> float:
    """kg/m² for validated positive measurements."""
    return weight_kg / ((height_cm / 100) ** 2)
