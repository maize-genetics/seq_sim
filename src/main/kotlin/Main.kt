package net.maizegenetics

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import net.maizegenetics.commands.AlignAssemblies
import net.maizegenetics.commands.AlignMutatedAssemblies
import net.maizegenetics.commands.BuildSplineKnots
import net.maizegenetics.commands.ConvertCoordinates
import net.maizegenetics.commands.ConvertRopebwt2Ps4g
import net.maizegenetics.commands.ConvertToFasta
import net.maizegenetics.commands.CreateChainFiles
import net.maizegenetics.commands.DownsampleGvcf
import net.maizegenetics.commands.ExtractChromIds
import net.maizegenetics.commands.FormatRecombinedFastas
import net.maizegenetics.commands.GenerateRecombinedSequences
import net.maizegenetics.commands.MafToGvcf
import net.maizegenetics.commands.Orchestrate
import net.maizegenetics.commands.PickCrossovers
import net.maizegenetics.commands.RopeBwtChrIndex
import net.maizegenetics.commands.RopeBwtMem
import net.maizegenetics.commands.SetupEnvironment

class SeqSim : CliktCommand() {
    override fun run() = Unit
}

fun main(args: Array<String>) = SeqSim()
    .subcommands(
        Orchestrate(),
        SetupEnvironment(),
        AlignAssemblies(),
        MafToGvcf(),
        DownsampleGvcf(),
        ConvertToFasta(),
        AlignMutatedAssemblies(),
        PickCrossovers(),
        CreateChainFiles(),
        ConvertCoordinates(),
        GenerateRecombinedSequences(),
        FormatRecombinedFastas(),
        RopeBwtChrIndex(),
        RopeBwtMem(),
        BuildSplineKnots(),
        ConvertRopebwt2Ps4g(),
        ExtractChromIds()
    )
    .main(args)
