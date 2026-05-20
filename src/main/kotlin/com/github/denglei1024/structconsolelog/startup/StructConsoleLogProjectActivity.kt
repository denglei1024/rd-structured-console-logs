package com.github.denglei1024.structconsolelog.startup

import com.github.denglei1024.structconsolelog.services.StructuredLogProjectService
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

class StructConsoleLogProjectActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        project.service<StructuredLogProjectService>()
    }
}

