package com.github.pires.obd.reader.config

import com.github.pires.obd.commands.ObdCommand
import com.github.pires.obd.commands.SpeedCommand
import com.github.pires.obd.commands.control.*
import com.github.pires.obd.commands.engine.*
import com.github.pires.obd.commands.fuel.*
import com.github.pires.obd.commands.pressure.*
import com.github.pires.obd.commands.temperature.*
import com.github.pires.obd.enums.FuelTrim
import java.util.*

object ObdConfig {
    fun getCommands(): List<ObdCommand> {
        val cmds = ArrayList<ObdCommand>()

        // Control
        cmds.add(ModuleVoltageCommand())
        cmds.add(EquivalentRatioCommand())
        cmds.add(DistanceMILOnCommand())
        cmds.add(DtcNumberCommand())
        cmds.add(TimingAdvanceCommand())
        cmds.add(TroubleCodesCommand())
        cmds.add(VinCommand())

        // Engine
        cmds.add(LoadCommand())
        cmds.add(RPMCommand())
        cmds.add(RuntimeCommand())
        cmds.add(MassAirFlowCommand())
        cmds.add(ThrottlePositionCommand())

        // Fuel
        cmds.add(FindFuelTypeCommand())
        cmds.add(ConsumptionRateCommand())
        cmds.add(FuelLevelCommand())
        cmds.add(FuelTrimCommand(FuelTrim.LONG_TERM_BANK_1))
        cmds.add(FuelTrimCommand(FuelTrim.LONG_TERM_BANK_2))
        cmds.add(FuelTrimCommand(FuelTrim.SHORT_TERM_BANK_1))
        cmds.add(FuelTrimCommand(FuelTrim.SHORT_TERM_BANK_2))
        cmds.add(AirFuelRatioCommand())
        cmds.add(WidebandAirFuelRatioCommand())
        cmds.add(OilTempCommand())

        // Pressure
        cmds.add(BarometricPressureCommand())
        cmds.add(FuelPressureCommand())
        cmds.add(FuelRailPressureCommand())
        cmds.add(IntakeManifoldPressureCommand())

        // Temperature
        cmds.add(AirIntakeTemperatureCommand())
        cmds.add(AmbientAirTemperatureCommand())
        cmds.add(EngineCoolantTemperatureCommand())

        // Misc
        cmds.add(SpeedCommand())

        return cmds
    }
}
