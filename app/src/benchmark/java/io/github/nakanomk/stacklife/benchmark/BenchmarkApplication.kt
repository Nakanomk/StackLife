package io.github.nakanomk.stacklife.benchmark

import io.github.nakanomk.stacklife.StackLifeApplication
import io.github.nakanomk.stacklife.transitions.TaskCardPilot
import io.github.nakanomk.stacklife.transitions.TaskCardTrial

internal class BenchmarkApplication : StackLifeApplication(), TaskCardTrial by TaskCardPilot
