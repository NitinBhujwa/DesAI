package com.example.storage

import com.example.model.AppJson
import com.example.model.Node
import com.example.model.Project
import com.example.model.Screen
import com.example.model.ThemeTokens
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

interface ProjectRepository {
  suspend fun list(): List<Project>
  suspend fun get(id: String): Project?
  suspend fun save(project: Project)
  suspend fun delete(id: String): Boolean
}

class FileProjectRepository(private val rootDir: File) : ProjectRepository {
  private val mutex = Mutex()
  private val projectsDir: File = File(rootDir, "projects").apply { mkdirs() }

  override suspend fun list(): List<Project> =
    withContext(Dispatchers.IO) {
      mutex.withLock {
        val files =
          projectsDir.listFiles { file -> file.isFile && file.extension == "json" }
            ?: emptyArray()
        files
          .mapNotNull { file ->
            runCatching {
              AppJson.instance.decodeFromString<Project>(file.readText(Charsets.UTF_8))
            }.getOrNull()
          }
          .sortedByDescending { it.updatedAt }
      }
    }

  override suspend fun get(id: String): Project? =
    withContext(Dispatchers.IO) {
      mutex.withLock {
        val file = fileForId(id)
        if (!file.exists()) return@withLock null
        runCatching {
          AppJson.instance.decodeFromString<Project>(file.readText(Charsets.UTF_8))
        }.getOrNull()
      }
    }

  override suspend fun save(project: Project) =
    withContext(Dispatchers.IO) {
      mutex.withLock {
        if (!projectsDir.exists()) {
          projectsDir.mkdirs()
        }
        val file = fileForId(project.id)
        val encoded = AppJson.instance.encodeToString(Project.serializer(), project)
        file.writeText(encoded, Charsets.UTF_8)
      }
    }

  override suspend fun delete(id: String): Boolean =
    withContext(Dispatchers.IO) {
      mutex.withLock {
        val file = fileForId(id)
        if (file.exists()) file.delete() else false
      }
    }

  suspend fun ensureSampleProject(): Project {
    val existing = get(SampleProjectFactory.SAMPLE_PROJECT_ID)
    if (existing != null) {
      // Ensure stored project passes current schema validation (e.g. after migrating alignment -> mainAxis/crossAxis)
      val firstScreen = existing.screens.firstOrNull()
      if (firstScreen != null && com.example.validator.Validator.validate(firstScreen).isEmpty()) {
        return existing
      }
    }
    val sample = SampleProjectFactory.createLoginSampleProject()
    save(sample)
    return sample
  }

  private fun fileForId(id: String): File {
    val safeId = id.replace(Regex("[^a-zA-Z0-9_-]"), "_")
    return File(projectsDir, "$safeId.json")
  }
}

object SampleProjectFactory {
  const val SAMPLE_PROJECT_ID = "proj_sample_login"
  const val SAMPLE_SCREEN_ID = "screen_login"

  fun createLoginSampleProject(nowMillis: Long = 1730419200000L): Project {
    val loginScreen = createLoginScreen()
    return Project(
      id = SAMPLE_PROJECT_ID,
      name = "Starter Workspace",
      screens = listOf(loginScreen),
      theme =
        ThemeTokens(
          primary = "#4F46E5",
          secondary = "#0EA5E9",
          background = "#F8FAFC",
          surface = "#FFFFFF",
          onPrimary = "#FFFFFF",
          cornerRadius = 10,
          fontScale = 1.0f,
        ),
      createdAt = nowMillis,
      updatedAt = nowMillis,
    )
  }

  fun createLoginScreen(): Screen {
    val root =
      Node(
        id = "root_column",
        type = "Column",
        props =
          buildJsonObject {
            put("width", JsonPrimitive("fill"))
            put("padding", JsonPrimitive(24))
            put("spacing", JsonPrimitive(16))
            put("mainAxis", JsonPrimitive("start"))
            put("crossAxis", JsonPrimitive("stretch"))
          },
        children =
          listOf(
            Node(
              id = "header_row",
              type = "Row",
              props =
                buildJsonObject {
                  put("width", JsonPrimitive("fill"))
                  put("spacing", JsonPrimitive(10))
                  put("mainAxis", JsonPrimitive("start"))
                  put("crossAxis", JsonPrimitive("center"))
                },
              children =
                listOf(
                  Node(
                    id = "brand_icon",
                    type = "Icon",
                    props =
                      buildJsonObject {
                        put("name", JsonPrimitive("lock"))
                        put("size", JsonPrimitive(28))
                        put("tint", JsonPrimitive("#4F46E5"))
                      },
                  ),
                  Node(
                    id = "title_text",
                    type = "Text",
                    props =
                      buildJsonObject {
                        put("text", JsonPrimitive("Welcome Back"))
                        put("fontSize", JsonPrimitive(24))
                        put("fontWeight", JsonPrimitive("bold"))
                        put("color", JsonPrimitive("#0F172A"))
                      },
                  ),
                ),
            ),
            Node(
              id = "subtitle_text",
              type = "Text",
              props =
                buildJsonObject {
                  put("text", JsonPrimitive("Sign in with your workspace credentials to continue."))
                  put("fontSize", JsonPrimitive(14))
                  put("fontWeight", JsonPrimitive("normal"))
                  put("color", JsonPrimitive("#475569"))
                },
            ),
            Node(
              id = "email_input",
              type = "TextField",
              props =
                buildJsonObject {
                  put("width", JsonPrimitive("fill"))
                  put("label", JsonPrimitive("Work Email"))
                  put("placeholder", JsonPrimitive("designer@studio.dev"))
                  put("value", JsonPrimitive("alex@studio.dev"))
                  put("inputType", JsonPrimitive("email"))
                },
            ),
            Node(
              id = "password_input",
              type = "TextField",
              props =
                buildJsonObject {
                  put("width", JsonPrimitive("fill"))
                  put("label", JsonPrimitive("Password"))
                  put("placeholder", JsonPrimitive("Enter your password"))
                  put("value", JsonPrimitive(""))
                  put("inputType", JsonPrimitive("password"))
                },
            ),
            Node(
              id = "form_spacer",
              type = "Spacer",
              props =
                buildJsonObject {
                  put("size", JsonPrimitive(8))
                },
            ),
            Node(
              id = "login_button",
              type = "Button",
              props =
                buildJsonObject {
                  put("width", JsonPrimitive("fill"))
                  put("text", JsonPrimitive("Sign In"))
                  put("variant", JsonPrimitive("filled"))
                  put("enabled", JsonPrimitive(true))
                },
            ),
          ),
      )

    return Screen(
      id = SAMPLE_SCREEN_ID,
      name = "Login",
      root = root,
      scrollable = true,
    )
  }
}
