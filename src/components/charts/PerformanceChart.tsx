import React, { useEffect, useRef } from 'react';

interface DataPoint {
  timestamp: number;
  value: number;
}

interface ChartSeries {
  name: string;
  data: DataPoint[];
  color?: string;
}

interface PerformanceChartProps {
  series: ChartSeries[];
  title?: string;
  yAxisLabel?: string;
  height?: number;
  width?: string;
  showLegend?: boolean;
}

const PerformanceChart: React.FC<PerformanceChartProps> = ({
  series,
  title = 'Performance',
  yAxisLabel = 'Value',
  height = 300,
  width = '100%',
  showLegend = true
}) => {
  const chartContainer = useRef<HTMLDivElement>(null);
  const chartInstance = useRef<any>(null);

  useEffect(() => {
    // Dynamically import ApexCharts to avoid SSR issues
    const loadChartLibrary = async () => {
      try {
        const ApexCharts = (await import('apexcharts')).default;
        
        if (chartContainer.current) {
          // Format the data for ApexCharts
          const formattedSeries = series.map(s => ({
            name: s.name,
            data: s.data.map(point => [point.timestamp, point.value]),
            color: s.color
          }));

          // Destroy previous chart if it exists
          if (chartInstance.current) {
            chartInstance.current.destroy();
          }

          // Chart options
          const options = {
            series: formattedSeries,
            chart: {
              type: 'line',
              height: height,
              toolbar: {
                show: true,
                tools: {
                  download: true,
                  selection: true,
                  zoom: true,
                  zoomin: true,
                  zoomout: true,
                  pan: true,
                  reset: true
                }
              },
              zoom: {
                enabled: true
              },
              background: '#000000',
              foreColor: '#22ff22' // Retro green text
            },
            stroke: {
              curve: 'smooth',
              width: 2
            },
            title: {
              text: title,
              align: 'left',
              style: {
                color: '#22ff22'
              }
            },
            grid: {
              borderColor: '#1E2125',
              row: {
                colors: ['#000000', '#0A0A0A'] // Alternating row colors
              }
            },
            xaxis: {
              type: 'datetime',
              labels: {
                style: {
                  colors: '#22ff22'
                }
              }
            },
            yaxis: {
              title: {
                text: yAxisLabel,
                style: {
                  color: '#22ff22'
                }
              },
              labels: {
                style: {
                  colors: '#22ff22'
                }
              }
            },
            legend: {
              show: showLegend,
              position: 'top',
              horizontalAlign: 'right',
              labels: {
                colors: '#22ff22'
              }
            },
            tooltip: {
              theme: 'dark'
            },
            dataLabels: {
              enabled: false
            },
            fill: {
              opacity: 0.2
            }
          };

          // Create the chart
          chartInstance.current = new ApexCharts(chartContainer.current, options);
          chartInstance.current.render();
        }
      } catch (error) {
        console.error("Error loading or rendering chart:", error);
      }
    };

    loadChartLibrary();

    // Cleanup function
    return () => {
      if (chartInstance.current) {
        chartInstance.current.destroy();
      }
    };
  }, [series, title, yAxisLabel, height, showLegend]);

  return (
    <div className="retro-card p-2" style={{ width }}>
      <div ref={chartContainer}></div>
    </div>
  );
};

export default PerformanceChart; 