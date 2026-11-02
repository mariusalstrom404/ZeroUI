# LLM setup and testing

The app calls OpenAI directly, using a key stored in one Kotlin file. You can test the same prompts in a local browser page without the app.

## 1. Set up OpenAI

In the [OpenAI dashboard](https://platform.openai.com) (menu names may differ slightly):

1. **Billing:** add a small prepaid credit and leave auto-recharge off.
2. **Project:** create a project called `ZeroUI`.
3. **Spend limit:** set a low monthly limit. It may not stop requests immediately, so treat the prepaid balance as the real cap.
4. **Model:** choose a low-cost model that supports Structured Outputs, then allow only that model in the project's limits.
5. **API key:** create the key in the `ZeroUI` project, with write access to Responses and nothing else. Copy it now, because it's shown only once.

## 2. Add the key

Open `app/src/main/java/com/example/myapplication/nlp/LlmSecrets.kt` and fill in both values:

```kotlin
const val OPENAI_API_KEY = "your-key"
const val OPENAI_MODEL = "your-model-id"
```

Then rebuild the app. That's all the setup it needs.

### Keep the key out of GitHub

The GitHub repository is **public**, so git ignores `LlmSecrets.kt` to stop the key being pushed. Anyone who finds a pushed key can spend your credit, and it can stay in the git history even after you delete it.

Because the file isn't in git, a fresh clone won't compile until the file is added. Pick one way to get it to groupmates:

- **Simplest:** only you build the APK, and groupmates install it. They need no setup.
- **Groupmates build too, repository stays public:** send them `LlmSecrets.kt` privately. They put it in the path above.
- **Key in the repository:** first make the repository private under **Settings > General > Danger Zone** on GitHub, and add groupmates as collaborators. Then delete the `LlmSecrets.kt` line from `.gitignore` and commit the file. Groupmates then need no setup at all.

### If the key leaks

Anyone with the APK can extract the key, so don't post the APK publicly. If the key leaks, revoke it in the OpenAI dashboard, create a new one, update `LlmSecrets.kt` and rebuild.

## 3. Test without the app

You need [Node.js](https://nodejs.org). No other installation is needed. From the repository root, run:

```powershell
node tools/test-portal/server.mjs
```

Open <http://localhost:8787>, choose a type, fill in the fields and select **Send**. The page shows the status, the response time and the model's JSON. Press `Ctrl+C` in the terminal to stop it.

The portal uses the key, model, prompts and schemas from the app's files, and only your own computer can connect to it. If you edit a prompt, send again to try it, without restarting. Each successful request is a real OpenAI call and costs a small amount.

### New commands

Each response lists only the fields of the intent the model chose. A field of that intent shows `null` if the user didn't give it.

| Command | Expected result |
|---|---|
| `turn on the flashlight` | `FLASHLIGHT`, `enable: true` |
| `transfer 50 to Bob` | `TRANSFER`, `amount: 50`, `recipient: "Bob"`, empty `missing` |
| `send some money` | `TRANSFER`, with `AMOUNT` and `RECIPIENT` in `missing`, each with a question |
| `check my balance and take a screenshot` | Two commands: `CHECK_BALANCE`, then `TAKE_SCREENSHOT` |
| `order fish and chips from Joe's on Foodpanda` | One `APP_ORDER`, `item: "fish and chips"` |
| `幫我打開手電筒` | `FLASHLIGHT`, `enable: true` |
| `search for 牛肉麵 on Foodpanda` | `TYPE_TEXT`, `text: "牛肉麵"`, `app: "Foodpanda"` |
| `book the Hilton in Tokyo tomorrow for 3 nights for 2 adults and 1 child` | One `HOTEL`, `book: true`, dates worked out from **Today**, `adults: 2`, `children: 1` |
| `tap Settings` | `CLICK_TEXT`, `text: "Settings"` |
| `purple monkey dishwasher` | `UNKNOWN`, and not a guessed risky intent |

### Follow-up answers

In the app, this only runs for answers the local rules can't understand.

| Detail being collected | Question | Answer | Expected result |
|---|---|---|---|
| `AMOUNT` | `How much should I transfer?` | `fifty dollars` | `filled`, `value: "50"` |
| `CONFIRMATION` | `You're about to transfer 50 to Bob. Say confirm or cancel.` | `absolutely, send it` | `confirm` |
| `CONFIRMATION` | `You're about to transfer 50 to Bob. Say confirm or cancel.` | `what's the weather` | `unclear`, not `confirm` |
| `DATES` | `When are you staying?` | `the first weekend of December` | `filled`, with `check_in` and `check_out` |
| `HOTEL` | `Which hotel would you like?` | `you pick for me` | `no_preference` |

Model output can vary, so run the safety rows several times. A wrong `confirm` or a guessed `TRANSFER` is the failure to take most seriously.

### Errors

| Result | Likely cause | Fix |
|---|---|---|
| `Fill in ... in LlmSecrets.kt` | The key or model is still a placeholder | Complete step 2 |
| Status `400` | Wrong model ID, or the model doesn't support this request | Check `OPENAI_MODEL` |
| Status `401` | Key is wrong or revoked | Check `OPENAI_API_KEY` |
| Status `403` | Key or project doesn't allow Responses or this model | Check the key's permissions and the project's allowed models |
| Status `429` | Out of credit, over the spend limit or rate limited | Check OpenAI billing and limits |

The page shows OpenAI's own error message with each status. Requests use `store: false`, so don't expect their content in OpenAI's stored logs. Use the dashboard's usage page to see that requests arrive and what they cost.

### What the portal doesn't cover

The portal tests only the prompts and the model. These run inside the app, so test them on a phone afterwards:

- Local rules for follow-up answers, and the fallback to the LLM
- Asking for missing details, and confirmation before transfers and checkout
- Dropping invalid values, such as past dates or negative amounts
- Carrying out the action on the device

If the app says it can't reach its language service, try the same request in the portal. If the portal works, check the phone's internet connection and that the APK was rebuilt after you added the key.
