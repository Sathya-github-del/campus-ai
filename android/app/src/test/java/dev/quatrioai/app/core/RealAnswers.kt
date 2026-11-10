package dev.quatrioai.app.core

/**
 * Answers in the shape that surfaced the raw-syntax bug on a real device: a step-by-step quadratic
 * solution and a projectile-motion problem, both mixing Markdown with \( \) / \[ \] LaTeX.
 */
object RealAnswers {
    val QUADRATIC = """
To solve the quadratic equation \( 2x^2 + 3x - 2 = 0 \), we can use the quadratic formula:

\[
x = \frac{-b \pm \sqrt{b^2 - 4ac}}{2a}
\]

### Step 1: Identify the coefficients
- \( a = 2 \)
- \( b = 3 \)
- \( c = -2 \)

### Step 2: Calculate the discriminant
\[
b^2 - 4ac = 3^2 - 4 \times 2 \times (-2) = 9 + 16 = 25
\]

### Step 3: Apply the formula
\[
x = \frac{-3 \pm \sqrt{25}}{2 \times 2} = \frac{-3 \pm 5}{4}
\]

**Solutions:**
1. \( x_1 = \frac{-3 + 5}{4} = \frac{2}{4} = \frac{1}{2} \)
2. \( x_2 = \frac{-3 - 5}{4} = \frac{-8}{4} = -2 \)

---

So the roots are **\( x = \frac{1}{2} \)** and **\( x = -2 \)**.
""".trimIndent()

    val PROJECTILE = """
**Given:**
- Initial velocity \( u = 20 \, \text{m/s} \)
- Angle of projection \( \theta = 30^\circ \)
- \( g = 9.8 \, \text{m/s}^2 \)

**1. Time of flight**
\[
T = \frac{2u \sin\theta}{g} = \frac{2 \times 20 \times \sin 30^\circ}{9.8} = \frac{20}{9.8} \approx 2.04 \, \text{s}
\]

**2. Maximum height**
\[
H = \frac{u^2 \sin^2\theta}{2g} = \frac{400 \times 0.25}{19.6} \approx 5.10 \, \text{m}
\]

**3. Horizontal range**
\[
R = \frac{u^2 \sin 2\theta}{g} = \frac{400 \times \frac{\sqrt{3}}{2}}{9.8} \approx 35.35 \, \text{m}
\]

So the ball stays in the air for about **2.04 s**, rises **5.10 m** and lands **35.35 m** away.
""".trimIndent()
}
