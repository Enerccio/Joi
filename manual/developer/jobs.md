---
label: Jobs
icon: pulse
order: 744
covers:
  - joi-server/src/main/java/com/github/enerccio/joi/domain/jobs
  - joi-server/src/main/java/com/github/enerccio/joi/domain/chat/turn/TurnJob.java
  - joi-server/src/main/java/com/github/enerccio/joi/ui/jobs
  - joi-server/src/main/java/com/github/enerccio/joi/ui/main/JoiJobsView.java
  - joi-server/src/main/java/com/github/enerccio/joi/ui/main/JoiServlet.java
verified: 7c29655
---

# Jobs

The user side is in [Jobs](../user/administration/jobs.md). The log, the live list and `Jobs` itself are the platform's
(`Jobs`, `JobService`, `JobRecorder`, `JobsPanel`, `ActiveJobsGrid`); Joi names its work and adds one list.

## What the platform does

`JobServiceImpl` (wired in `services-config.xml`) records every request to a model as a **call** of the job of the calling thread
(`JobRecorder`, an `InferenceListener`), and a request outside any job becomes a job of its own, kind `untagged`. A job is written to the
log when it ends. The owner of a job is read from the session scoped `User` on the thread that opens it, so **open a job inside the
request scope of the work**: every thread of Joi's background work already runs in one (`InRequestScope` with the attributes of the user).

## What Joi tags

`domain/jobs/JoiJobs` has the kinds (`chat`, `summary`, `web-task`, `appearance`, `conversion`) and two helpers, `call` and `run`, that open a
job around work on this thread and end it as failed when the work throws.

| Work | Where |
|---|---|
| a chat turn | `TurnJob` in `ChatServiceImpl.TurnEngine` |
| a summary job | `SummarizerServiceImpl.Queued.run` |
| a task that reads the web | `WebTaskServiceImpl.next`, around `execute` |
| the description of a model or an outfit | `AppearanceServiceImpl` |
| a conversion | `ModelConversionServiceImpl`, `AnimationConversionServiceImpl`; Blender is a call of it (`Blender.run`) |
| a speech | `JobCalls.track` in `tts/http`: a call of the job of the thread, else a job `joi-tts` |

A turn runs on several threads (a virtual thread per step). `TurnJob` opens the job (`Jobs.openAsync`) on the thread of the first step, and every
other step and callback enters it (`Handle.enter`), next to the request scope in the same `try`. It ends with the turn: completed when a message
was stored, failed with a failure, cancelled when the turn was stopped.

## `/jobs` and the Administration tab

The tab is the platform's `JobsPanel`. The page `/jobs` is `ui/main/JoiJobsView`: the platform's active jobs, and below it
`ui/jobs/AssistantsRunsGrid`, which reads `ChatService.running()` (the turns that wait or run: `RunningTurn`), `SummarizerService.jobs(joi)` and
`JoiTaskService.findByStatus` for every assistant.

The platform has a page of the same address that names Marginalia (`ui.main.JobsView`, found by Vaadin's scan of the class path). A second
`@Route("jobs")` would be an error, so `JoiJobsView` has no route annotation and `JoiServlet.servletInitialized` replaces the platform's route in the
application's route registry.

## Tests

`ChatTurnTest` (a turn is one job with its requests as calls, owned by the user; a stopped turn is cancelled), `SummarizerTest` (a summary is a job),
`AssistantsRunsTest` (what the list shows). They take the finished jobs with `Jobs.addSink`; never `Jobs.reset()`: it would remove the service's own sink.
