# AI News Bias and Manipulation Risk Meter

Submit text, a screenshot, or a public link. The report compares up to three central claims with retrieved article passages, explains the wording signals, and stores the complete result in MySQL.

## Start on Windows

1. Enter a free [GNews API key](https://gnews.io/dashboard) as `GNEWS_API_KEY=...` in `.env`. Copy `.env.example` if needed. The free plan covers the previous 30 days, with a 12-hour delay and 100 daily requests.
2. Double-click `run_platform.bat`. It uses the installed MySQL 8, Java 21, Maven, Node/npm, and Python (3.12–3.14) on Windows. It prepares dependencies, then starts a dedicated MySQL instance, FastAPI, Spring Boot, and React in that order. The browser opens at http://localhost:5174. Keep the launcher window open and press Ctrl+C to stop the services. First launch and live providers need internet access.
3. The launcher reads Inception settings from the original education project when missing from the new configuration. Set `INCEPTION_API_KEY`, `INCEPTION_BASE_URL`, and `INCEPTION_MODEL` in the local `.env` to use another OpenAI-compatible provider. Missing GNews credentials allow language checks with an explicit limited assessment. Missing AI credentials cause an actionable job error.

The launcher generates project-specific MySQL credentials and a service token in `.runtime.env`. The Windows database uses port 3317 and lives under `.native/mysql`. Its logs and the service logs are in `.native`. Windows OCR uses the English language pack or a local Tesseract installation. Both environment files and the runtime folder are ignored by Git.

## Docker alternative

Run `run_platform.bat -Docker` with Docker Desktop's Linux engine available. The Compose stack includes its own MySQL volume and English Tesseract OCR. It publishes only the frontend on loopback. Keep `.runtime.env` with that volume.

```powershell
docker compose --env-file .runtime.env ps
docker compose --env-file .runtime.env logs --tail=100
docker compose --env-file .runtime.env down
```

The last command stops the services and preserves reports. `backend/src/main/resources/db/migration/V1__analyses.sql` is the idempotent initial schema applied by Spring Boot. Reports store the normalized submission, claims, flags, cited evidence, scores, and the actual rubric configuration. Queued or running jobs interrupted by a backend restart are marked failed and can be submitted again. The local stack uses one backend instance.

## Demo inputs

- **Text:** Use “Use sample text” in the form. Its synthetic Moon-size claim conflicts with [NASA’s Moon facts](https://science.nasa.gov/moon/facts/). The live verdict will depend on what the search provider retrieves. A recent false claim covered by several indexed publishers is better for a live contradiction demonstration.
- **Insufficient evidence:** Paste “The fictional town of Ember Quay reported that exactly 147 lavender streetlights changed color at 04:17 on Monday. No records or corroborating sources were cited.”
- **Screenshot:** Download the sample from the Upload image tab and upload it. The screenshot contains a synthetic claim and is a check of English OCR.
- **Link:** Use “Use sample link” to extract NASA’s Moon facts page. If live fetching is blocked, paste the text. The original URL cannot independently corroborate itself.

## REST examples

```powershell
curl.exe -F "type=text" -F "headline=Example headline" -F "text=Ember Quay reported that exactly 147 lavender streetlights changed color overnight." http://localhost:5174/api/analyses
curl.exe -F "type=url" -F "url=https://science.nasa.gov/moon/facts/" http://localhost:5174/api/analyses
curl.exe -F "type=image" -F "image=@frontend/public/demo-screenshot.png" http://localhost:5174/api/analyses
curl.exe http://localhost:5174/api/analyses/REPLACE_WITH_ID
```

POST returns 202 and a UUID. GET returns Queued, Running, Completed, or Failed with the stage, report, or user-facing error. The report URL uses the same UUID. Anyone who has access to the local app and knows that UUID can reopen the report. Article URLs and evidence passages are fetched over public HTTP(S) with bounded redirects, pinned public addresses, verified HTTPS, response limits, and timeouts.

## Provisional scoring rubric v1.0

| Manipulation risk | Label |
| --- | --- |
| 0–14 | Neutral |
| 15–34 | Mild Bias |
| 35–64 | Moderate Manipulation |
| 65–100 | High Manipulation Risk |

Manipulation risk combines the accepted wording and framing points and claim verdict points, capped at 100. A cited contradiction contributes 32 and Missing Context contributes 12. Supported and Insufficient Evidence contribute zero. Loaded language, one-sided framing, clickbait, headline mismatch, exaggeration, and material omission carry 7, 10, 8, 14, 11, and 15 base points respectively. Low, medium, and high severity multiply these by 0.5, 1, and 1.5, rounded to the nearest integer. An excerpt must match the extracted article verbatim. Exaggeration and material omission need evidence. The backend keeps the strongest overlapping excerpt and filters exact normalized repeats in excerpts, reasons, and claims. Each wording category contributes at most twice. Bias is three times the accepted loaded-language and one-sided-framing points, capped at 100.

Credibility is the mean of assessable claim scores: Supported 100, Missing Context 50, and Contradicted 0. It requires usable evidence for at least half of the extracted claims and at least one claim. Results show the evidence coverage. A result with no supported evaluation of any claim is marked language-only. Scores describe retrieved evidence and editorial signals, with no inference of intent.

Change weights and thresholds in `backend/src/main/resources/application.properties` and bump the rubric version when changing the rules. Source type and relevance are assessed from the passages. Search coverage, access restrictions, OCR quality, and provider output can affect the result.

